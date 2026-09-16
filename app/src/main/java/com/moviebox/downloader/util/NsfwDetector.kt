package com.moviebox.downloader.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.moviebox.downloader.api.MovieBoxApi
import com.moviebox.downloader.debug.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.ByteBuffer

/**
 * Optional, model-agnostic NSFW poster classifier — the SafeSearch image signal.
 *
 * MODEL-AGNOSTIC: it auto-detects whatever `.tflite` is present in `assets/`
 * (see [MODEL_CANDIDATES] then a scan for any `*.tflite`) and inspects the
 * interpreter's input/output tensors at load time, so it works with the common
 * NSFW models without code changes. If no model is bundled the classifier
 * simply reports "no verdict" (null) and [ContentAnalyzer] falls back to text.
 *
 * Output conventions (assumed — flip the constants if your model differs):
 *   - 1 class  : value is the NSFW probability in [0,1]
 *   - 2 classes: [SFW, NSFW]              -> NSFW = index 1
 *   - 3 classes: [NSFW, SEXY, SAFE]       -> NSFW = index 0, SEXY contributes weakly
 *
 * Input conventions (assumed — see [IMAGE_MEAN]/[IMAGE_STD]):
 *   - UINT8 model  : raw pixels [0,255]
 *   - FLOAT model  : pixels normalized to [0,1] via NormalizeOp(0, 255)
 *
 * Optimized for low-RAM devices: model loaded once & reused, LRU cache of
 * poster verdicts, small bitmap decode, bounded concurrency (2), fail-open.
 */
object NsfwDetector {

    private const val TAG = "NsfwDetector"
    private const val INPUT_SIZE = 224
    private const val POSTER_MAX_DIM = 160
    private const val CACHE_MAX_SIZE = 300
    private const val MAX_CONCURRENT = 2
    private const val INFER_TIMEOUT_MS = 8_000L

    /** Try these exact names first, then fall back to any *.tflite in assets/. */
    private val MODEL_CANDIDATES = listOf(
        "nsfw_mobilenet_v2_quantized.tflite",
        "nsfw_model.tflite",
        "nsfw.tflite",
    )

    // Output layout (see class KDoc). Flip if your model uses a different order.
    private const val SFW_INDEX = 0          // 2-class models: index 0 = SFW
    private const val SEXY_WEIGHT = 0.7f     // SEXY class is a weaker adult signal

    // Preprocessing for the input image.
    //
    // UINT8 (quantized) models consume raw 0-255 pixels directly.
    // FLOAT models vary: the bundled open_nsfw-style ResNet also consumes
    // raw 0-255 pixels at its float input, while the popular MobileNetV2
    // NSFW exports expect [0,1]. TFLite 2.14 does not expose the input
    // tensor's min/max stats to Java, so the float convention is declared
    // here. Set INPUT_FLOAT_RAW_PIXELS = false (and keep the NormalizeOp)
    // if you swap in a [0,1] model.
    private const val INPUT_FLOAT_RAW_PIXELS = true
    private const val IMAGE_MEAN = 0f
    private const val IMAGE_STD = 255f

    @Volatile
    private var interpreter: Interpreter? = null
    @Volatile
    private var inputIsFloat = false
    @Volatile
    private var outputLen = 0
    private val inferenceSemaphore = Semaphore(MAX_CONCURRENT)
    private val lruCache = object : LinkedHashMap<String, Float?>(CACHE_MAX_SIZE, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Float?>?) = size > CACHE_MAX_SIZE
    }
    @Volatile
    private var modelLoaded = false
    @Volatile
    private var modelLoadFailed = false
    private val lock = Any()

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("moviebox_settings", Context.MODE_PRIVATE)
            .getBoolean("nsfw_detection_enabled", true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("moviebox_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("nsfw_detection_enabled", enabled).apply()
    }

    /**
     * Returns NSFW probability in [0,1], or null if disabled / download failed.
     * Uses a bundled TFLite model when present, otherwise falls back to the
     * always-on [ImageNsfwAnalyzer] heuristic so the image signal is never dead.
     */
    suspend fun nsfwScore(context: Context, posterUrl: String): Float? = withContext(Dispatchers.IO) {
        if (!isEnabled(context)) return@withContext null

        // Cache hit — zero cost, no network.
        lruCache[posterUrl]?.let { return@withContext it }

        val bitmap = downloadThumbnail(posterUrl) ?: return@withContext null

        inferenceSemaphore.withPermit {
            try {
                val interp = getInterpreter(context)
                val score = withTimeoutOrNull(INFER_TIMEOUT_MS) {
                    if (interp != null) runInference(interp, bitmap)
                    else ImageNsfwAnalyzer.score(bitmap)   // always-on, no model needed
                }
                lruCache[posterUrl] = score
                val method = if (interp != null) "model" else "heuristic (no model bundled)"
                AppLog.i(
                    TAG,
                    "NSFW $method: ${(score?.times(100))?.toInt() ?: 0}% — $posterUrl",
                )
                score
            } catch (e: Exception) {
                AppLog.warn(TAG, "NSFW analysis failed: ${e.message}")
                null
            } finally {
                recycleQuietly(bitmap)
            }
        }
    }

    private fun getInterpreter(context: Context): Interpreter? {
        if (modelLoaded) return interpreter
        if (modelLoadFailed) return null

        synchronized(lock) {
            if (modelLoaded) return interpreter
            if (modelLoadFailed) return null

            val name = MODEL_CANDIDATES.firstOrNull { exists(context, it) } ?: firstTflite(context)
            if (name == null) {
                modelLoadFailed = true
                AppLog.e(
                    TAG,
                    "No .tflite model found in assets/ — image NSFW detection is disabled; " +
                        "the text filter still runs. Drop a model into app/src/main/assets/ to enable it.",
                )
                return null
            }
            try {
                context.assets.openFd(name).use { afd ->
                    afd.createInputStream().use { input ->
                        val buf = ByteBuffer.allocateDirect(afd.length.toInt())
                        val tmp = ByteArray(8192)
                        var n: Int
                        while (input.read(tmp).also { n = it } > 0) buf.put(tmp, 0, n)
                        buf.rewind()

                        val opts = Interpreter.Options().apply { setNumThreads(2) }
                        val interp = Interpreter(buf, opts)
                        inputIsFloat = interp.getInputTensor(0).dataType() == DataType.FLOAT32
                        outputLen = interp.getOutputTensor(0).shape().last().coerceAtLeast(1)
                        interpreter = interp
                        modelLoaded = true
                        AppLog.ok(
                            TAG,
                            "NSFW model loaded: $name (${afd.length.toInt() / 1024}KB, " +
                                "input=${if (inputIsFloat) "FLOAT32" else "UINT8"} " +
                                "(preprocessing=${if (inputIsFloat && !INPUT_FLOAT_RAW_PIXELS) "0-1 normalized" else "raw 0-255"}), " +
                                "classes=$outputLen)",
                        )
                        return interp
                    }
                }
            } catch (e: Exception) {
                modelLoadFailed = true
                AppLog.e(TAG, "NSFW model load failed: ${e.javaClass.simpleName}: ${e.message}")
                return null
            }
        }
    }

    private fun exists(context: Context, name: String): Boolean =
        try {
            context.assets.openFd(name).close()
            true
        } catch (_: Exception) {
            false
        }

    private fun firstTflite(context: Context): String? =
        (context.assets.list("") ?: emptyArray()).firstOrNull { it.endsWith(".tflite", true) }

    private suspend fun downloadThumbnail(posterUrl: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val bytes = MovieBoxApi.downloadBytes(posterUrl) ?: return@withContext null
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, this)
                if (outWidth <= 0 || outHeight <= 0) return@withContext null
                inSampleSize = calculateInSampleSize(outWidth, outHeight, POSTER_MAX_DIM)
                inJustDecodeBounds = false
                inPreferredConfig = Bitmap.Config.RGB_565 // 2 bytes/px instead of 4
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            AppLog.warn(TAG, "NSFW thumbnail download failed: ${e.message}")
            null
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var s = 1
        if (height > maxDim || width > maxDim) {
            val hh = height / 2
            val ww = width / 2
            while (hh / s >= maxDim && ww / s >= maxDim) s *= 2
        }
        return s
    }

    private fun runInference(interpreter: Interpreter, bitmap: Bitmap): Float {
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val src = if (resized != bitmap) {
            recycleQuietly(bitmap)
            resized
        } else {
            bitmap
        }

        val tensor = TensorImage(if (inputIsFloat) DataType.FLOAT32 else DataType.UINT8).apply { load(src) }
        val builder = ImageProcessor.Builder()
            .add(ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
        if (inputIsFloat && !INPUT_FLOAT_RAW_PIXELS) {
            builder.add(NormalizeOp(IMAGE_MEAN, IMAGE_STD))
        }
        val processed = builder.build().process(tensor)

        val out = Array(1) { FloatArray(outputLen) }
        interpreter.run(processed.buffer, out)
        val s = out[0]
        recycleQuietly(src)

        val nsfw = when (outputLen) {
            1 -> s[0].coerceIn(0f, 1f)
            2 -> s[SFW_INDEX + 1]                 // [SFW, NSFW]
            3 -> maxOf(s[0], s[1] * SEXY_WEIGHT)  // [NSFW, SEXY, SAFE]
            else -> s.last()
        }
        AppLog.i(TAG, "NSFW scores: ${s.joinToString()}")
        return nsfw.coerceIn(0f, 1f)
    }

    private fun recycleQuietly(b: Bitmap?) {
        try {
            if (b != null && !b.isRecycled) b.recycle()
        } catch (_: Exception) {
            // ignore
        }
    }

    fun clearCache() {
        synchronized(lock) {
            lruCache.clear()
            AppLog.i(TAG, "NSFW cache cleared")
        }
    }

    fun cacheSize(): Int = lruCache.size
}
