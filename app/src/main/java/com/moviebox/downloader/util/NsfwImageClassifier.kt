package com.moviebox.downloader.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.moviebox.downloader.debug.AppLog
import okhttp3.OkHttpClient
import okhttp3.Request
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit

/**
 * SafeSearch WALL 2 — on-device NSFW poster classifier.
 *
 * Runs the GantMan/nsfw_model 5-class MobileNetV2 (drawings / hentai /
 * neutral / porn / sexy) fully on-device via TensorFlow Lite:
 *   - no API keys, no account, no per-image cost
 *   - no image ever leaves the phone
 *   - ~50-150 ms per cover on a mid-range device
 *
 * Wall 1 (ContentFilter) blocks adult NAMES; this catches adult IMAGES
 * whose title/description/genre look clean.
 *
 * STRICT blocking profile (tune here — single place):
 *   porn    >= 0.40  -> block (real explicit imagery)
 *   hentai  >= 0.40  -> block (explicit anime — this catalog's weak spot)
 *   sexy    >= 0.80  -> block alone (near-explicit)
 *   sexy    >= 0.55  -> block WHEN the title also carries a soft suggestive
 *                       marker (ContentFilter.hasSoftSignal) — combined vote
 *
 * Deliberately NEVER blocks on "drawings" (safe anime/CGI art — Avatar,
 * One Piece etc. score there) and never blocks on "sexy" below 0.55 alone
 * (normal movie posters with attractive cast score 0.1-0.5).
 *
 * Fail-open by design: if the model can't load, an image can't be decoded
 * or inference throws, the classifier reports "not NSFW" — wall 1 still
 * works, and a broken model must never hide legit content.
 */
object NsfwImageClassifier {

    private const val TAG = "NsfwClassifier"

    /** 5-class output order fixed by class_labels.txt shipped with the model. */
    data class Scores(
        val drawings: Float,
        val hentai: Float,
        val neutral: Float,
        val porn: Float,
        val sexy: Float,
    ) {
        fun top(): String {
            val all = listOf(
                "drawings" to drawings, "hentai" to hentai, "neutral" to neutral,
                "porn" to porn, "sexy" to sexy,
            ).sortedByDescending { it.second }
            return all.joinToString(" ") { "${it.first}=${"%.2f".format(it.second)}" }
        }
    }

    data class Verdict(
        val isNsfw: Boolean,
        val reason: String?,   // human-readable reason when blocked, else null
        val scores: Scores?,
    ) {
        companion object {
            /** Fail-open verdict — used when the classifier is unavailable. */
            val PASS = Verdict(false, null, null)
        }
    }

    /* ---------- STRICT thresholds (the only tuning knobs) ---------- */
    private const val THRESH_PORN = 0.40f
    private const val THRESH_HENTAI = 0.40f
    private const val THRESH_SEXY_ALONE = 0.80f
    private const val THRESH_SEXY_WITH_SOFT_MARKER = 0.55f

    private const val INPUT_SIZE = 224
    private const val MODEL_FILE = "nsfw_classifier.tflite"
    private const val MAX_COVER_BYTES = 8 * 1024 * 1024   // 8 MB safety cap

    /* ---------- interpreter (lazy singleton) ---------- */

    @Volatile
    private var interpreter: Interpreter? = null
    private val initLock = Any()

    /** Inference is serialized — TFLite Interpreter.run() is not thread-safe. */
    private val inferenceLock = Any()

    private var http: OkHttpClient? = null

    /** Loads the model from assets once. Cheap after the first call. */
    fun ensureLoaded(context: Context): Boolean {
        synchronized(initLock) {
            interpreter?.let { return true }
            return try {
                val t0 = System.currentTimeMillis()
                context.assets.openFd(MODEL_FILE).use { fd ->
                    val channel = fd.createInputStream().channel
                    val mapped = channel.map(
                        FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength,
                    )
                    interpreter = Interpreter(
                        mapped,
                        Interpreter.Options().apply { numThreads = 2 },
                    )
                }
                AppLog.ok(
                    AppLog.CAT_UI,
                    "NSFW model loaded (17 MB, 5-class, ${System.currentTimeMillis() - t0} ms)"
                )
                true
            } catch (e: Exception) {
                AppLog.warn(
                    AppLog.CAT_UI,
                    "NSFW model FAILED to load — image wall disabled, text wall still active",
                    "${e.javaClass.simpleName}: ${e.message ?: ""}"
                )
                false
            }
        }
    }

    fun isReady(): Boolean = interpreter != null

    /* ---------- classification ---------- */

    /**
     * Classify a decoded bitmap. Returns null on failure (fail-open).
     * The caller is responsible for calling [ensureLoaded] first.
     */
    fun classifyBitmap(src: Bitmap): Scores? {
        val itp = interpreter ?: return null
        return try {
            val scaled = Bitmap.createScaledBitmap(src, INPUT_SIZE, INPUT_SIZE, true)
            val px = IntArray(INPUT_SIZE * INPUT_SIZE)
            scaled.getPixels(px, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

            // MobileNetV2 preprocessing: (pixel / 127.5) - 1.0, RGB order.
            val buf = ByteBuffer.allocateDirect(4 * px.size * 3)
                .order(ByteOrder.nativeOrder())
            for (p in px) {
                buf.putFloat(((p shr 16 and 0xFF) / 127.5f) - 1f)  // R
                buf.putFloat(((p shr 8 and 0xFF) / 127.5f) - 1f)   // G
                buf.putFloat(((p and 0xFF) / 127.5f) - 1f)         // B
            }
            buf.rewind()

            val out = Array(1) { FloatArray(5) }
            synchronized(inferenceLock) {
                itp.run(buf, out)
            }
            Scores(
                drawings = out[0][0],
                hentai = out[0][1],
                neutral = out[0][2],
                porn = out[0][3],
                sexy = out[0][4],
            )
        } catch (e: Exception) {
            AppLog.warn(AppLog.CAT_UI, "NSFW inference failed", "${e.javaClass.simpleName}: ${e.message ?: ""}")
            null
        }
    }

    /**
     * Download a cover URL, decode downsampled, classify.
     * Returns the scores, or null when unavailable (fail-open).
     */
    fun classifyUrl(url: String, timeoutMs: Long = 8_000): Scores? {
        if (url.isEmpty()) return null
        val client = http ?: OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
            .also { http = it }
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return null
                val bytes = r.body?.bytes() ?: return null
                if (bytes.isEmpty() || bytes.size > MAX_COVER_BYTES) return null
                val bmp = decodeDownsampled(bytes) ?: return null
                classifyBitmap(bmp)
            }
        } catch (e: Exception) {
            null   // fail-open — network/decode problems never hide content
        }
    }

    /** Decode bounded to ~2x the model input to keep memory + time small. */
    private fun decodeDownsampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= INPUT_SIZE &&
            bounds.outHeight / (sample * 2) >= INPUT_SIZE
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /* ---------- verdict ---------- */

    /**
     * STRICT verdict. [softTextSignal] is ContentFilter.hasSoftSignal(title):
     * suggestive-but-innocent wording that, combined with a sexy-leaning
     * poster, pushes borderline softcore over the line.
     */
    fun verdict(s: Scores?, softTextSignal: Boolean): Verdict {
        if (s == null) return Verdict.PASS
        val reasons = mutableListOf<String>()
        if (s.porn >= THRESH_PORN) {
            reasons += "explicit imagery (porn ${"%.2f".format(s.porn)})"
        }
        if (s.hentai >= THRESH_HENTAI) {
            reasons += "explicit anime (hentai ${"%.2f".format(s.hentai)})"
        }
        if (s.sexy >= THRESH_SEXY_ALONE) {
            reasons += "near-explicit imagery (sexy ${"%.2f".format(s.sexy)})"
        } else if (softTextSignal && s.sexy >= THRESH_SEXY_WITH_SOFT_MARKER) {
            reasons += "suggestive imagery + suggestive title (sexy ${"%.2f".format(s.sexy)})"
        }
        return if (reasons.isEmpty()) Verdict(false, null, s)
        else Verdict(true, reasons.joinToString("; "), s)
    }

    /**
     * One-shot helper used by MainViewModel: ensure the model is loaded,
     * classify the cover URL, and return the STRICT verdict.
     */
    fun classifyCoverBlocking(context: Context, coverUrl: String, title: String): Verdict {
        if (!ensureLoaded(context)) return Verdict.PASS
        val scores = classifyUrl(coverUrl)
        val v = verdict(scores, ContentFilter.hasSoftSignal(title))
        if (scores != null) {
            AppLog.i(
                AppLog.CAT_UI,
                "NSFW image check \"${title.take(40)}\": ${scores.top()}" +
                    if (v.isNsfw) " -> BLOCKED (${v.reason})" else " -> pass"
            )
        }
        return v
    }
}
