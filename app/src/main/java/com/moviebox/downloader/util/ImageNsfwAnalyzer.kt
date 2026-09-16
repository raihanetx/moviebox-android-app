package com.moviebox.downloader.util

import android.graphics.Bitmap
import kotlin.collections.ArrayDeque

/**
 * Always-on, model-free NSFW image analyzer — the primary SafeSearch signal.
 *
 * Runs when no TFLite model is bundled in assets/. It is a lightweight visual
 * heuristic — not a trained classifier — but tuned to catch EXPLICIT posters
 * (nudity / heavy skin coverage), which is what the title/description keyword
 * blocklist cannot: a clean-titled item with a sexual cover.
 *
 * Two complementary cues, both scale-invariant on a downscaled bitmap:
 *   1. overall skin/flesh-tone RATIO  (Kovac et al. RGB skin rule)
 *   2. largest CONNECTED skin REGION  (a nude body forms one big patch; a face
 *      is a much smaller patch — this is what keeps cast/face posters safe)
 *
 * The score is the max of the two cues, mapped to [0,1]. A real TFLite model
 * (see [NsfwDetector]) overrides this for suggestive-but-clothed accuracy.
 */
object ImageNsfwAnalyzer {

    private const val MAX_DIM = 96       // downscale for speed
    private const val RATIO_FLOOR = 0.15f
    private const val RATIO_CEIL = 0.50f
    private const val BLOB_FLOOR = 0.20f // largest skin patch must cover this fraction to count
    private const val BLOB_CEIL = 0.45f

    /** Returns an NSFW likelihood in [0,1] for the given bitmap. */
    fun score(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return 0f

        val bmp = if (maxOf(w, h) > MAX_DIM) {
            val scale = MAX_DIM.toFloat() / maxOf(w, h)
            Bitmap.createScaledBitmap(bitmap, maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()), true)
        } else {
            bitmap
        }

        val ww = bmp.width
        val hh = bmp.height
        val pixels = IntArray(ww * hh)
        bmp.getPixels(pixels, 0, ww, 0, 0, ww, hh)

        val skin = BooleanArray(pixels.size)
        var skinCount = 0
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            val isSkin = r > 95 && g > 40 && b > 20 &&
                (mx - mn) > 15 && kotlin.math.abs(r - g) > 15 && r > g && r > b
            skin[i] = isSkin
            if (isSkin) skinCount++
        }

        val ratio = skinCount.toFloat() / pixels.size
        val largestBlob = largestSkinBlob(skin, ww, hh)

        if (bmp !== bitmap) bmp.recycle()

        val ratioScore = ((ratio - RATIO_FLOOR) / (RATIO_CEIL - RATIO_FLOOR)).coerceIn(0f, 1f)
        val blobScore = ((largestBlob - BLOB_FLOOR) / (BLOB_CEIL - BLOB_FLOOR)).coerceIn(0f, 1f)
        return maxOf(ratioScore, blobScore)
    }

    /** Largest 4-connected skin region, as a fraction of the whole image. */
    private fun largestSkinBlob(skin: BooleanArray, w: Int, h: Int): Float {
        val visited = BooleanArray(skin.size)
        val stack = ArrayDeque<Int>()
        var largest = 0
        for (start in skin.indices) {
            if (!skin[start] || visited[start]) continue
            var size = 0
            stack.addLast(start)
            visited[start] = true
            while (stack.isNotEmpty()) {
                val idx = stack.removeLast()
                size++
                val x = idx % w
                val y = idx / w
                if (x > 0 && skin[idx - 1] && !visited[idx - 1]) { visited[idx - 1] = true; stack.addLast(idx - 1) }
                if (x < w - 1 && skin[idx + 1] && !visited[idx + 1]) { visited[idx + 1] = true; stack.addLast(idx + 1) }
                if (y > 0 && skin[idx - w] && !visited[idx - w]) { visited[idx - w] = true; stack.addLast(idx - w) }
                if (y < h - 1 && skin[idx + w] && !visited[idx + w]) { visited[idx + w] = true; stack.addLast(idx + w) }
            }
            if (size > largest) largest = size
        }
        return largest.toFloat() / skin.size
    }
}
