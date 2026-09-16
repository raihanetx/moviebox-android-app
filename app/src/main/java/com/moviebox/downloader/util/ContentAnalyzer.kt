package com.moviebox.downloader.util

import android.content.Context
import com.moviebox.downloader.debug.AppLog

/**
 * Combined content analyzer — the new SafeSearch core.
 *
 * Merges three independent signals into a single, explainable verdict:
 *   1. site metadata  — the site's own Adult/Erotic genre tags (decisive)
 *   2. text           — title/description/genre keywords (via ContentFilter)
 *   3. image (optional) — a TFLite NSFW model on the poster (NsfwDetector)
 *
 * The image model is optional: if no `.tflite` is bundled, [analyze] simply
 * falls back to the text signal, so the app still blocks known adult content.
 * Signals are combined with a probabilistic-OR, which is the key fix vs. the
 * old pipeline — a clean-titled item with an explicit poster now gets blocked
 * even though its text is innocent.
 *
 * Verdicts:
 *   NSFW       -> block (SafeSearch). score >= 0.5
 *   SUGGESTIVE -> flagged/identified but not hard-blocked. 0.2 <= score < 0.5
 *   SAFE       -> allow. score < 0.2
 */
object ContentAnalyzer {

    private const val NSFW_THRESHOLD = 0.5f
    private const val SUGGESTIVE_THRESHOLD = 0.2f
    // A poster scoring at/above this on the IMAGE analyzer alone blocks,
    // regardless of the text signal — the image is the primary SafeSearch signal.
    private const val IMAGE_NSFW = 0.45f

    enum class Verdict { SAFE, SUGGESTIVE, NSFW }

    data class Analysis(
        val verdict: Verdict,
        val score: Float,          // 0..1 combined adult likelihood
        val confidence: Float,     // 0..1
        val reasons: List<String>,
    )

    /** Pure text analysis — safe on the main thread (used by list filtering). */
    fun analyzeText(title: String, description: String, genres: List<String>): Analysis {
        val f = ContentFilter.filter(title, description, genres)
        return build(f.score, null, f.reasons)
    }

    fun isAdultText(title: String, genres: List<String>): Boolean =
        analyzeText(title, "", genres).verdict == Verdict.NSFW

    fun isAdultDetail(title: String, description: String, genres: List<String>): Boolean =
        analyzeText(title, description, genres).verdict == Verdict.NSFW

    fun analyzeQuery(q: String): Analysis = analyzeText(q, "", emptyList())

    fun isAdultQuery(q: String): Boolean = analyzeQuery(q).verdict == Verdict.NSFW

    /** Full analysis including the optional poster image model. */
    suspend fun analyze(
        context: Context,
        title: String,
        description: String,
        genres: List<String>,
        coverUrl: String? = null,
    ): Analysis {
        val f = ContentFilter.filter(title, description, genres)
        val reasons = f.reasons.toMutableList()
        var image: Float? = null
        if (!coverUrl.isNullOrBlank() && NsfwDetector.isEnabled(context)) {
            val img = NsfwDetector.nsfwScore(context, coverUrl)
            if (img != null) {
                image = img
                reasons += "poster model: ${(img * 100).toInt()}% NSFW"
            } else {
                reasons += "poster model: no verdict (no model / disabled)"
            }
        }
        return build(f.score, image, reasons)
    }

    fun isAdult(a: Analysis): Boolean = a.verdict == Verdict.NSFW

    private fun build(text: Float, image: Float?, reasons: List<String>): Analysis {
        // Probabilistic-OR: a clean title + explicit poster still blocks.
        val combined = if (image == null) text else 1f - (1f - text) * (1f - image)
        val verdict = when {
            // Image is the primary signal: a poster scoring moderate on its own blocks.
            image != null && image >= IMAGE_NSFW -> Verdict.NSFW
            combined >= NSFW_THRESHOLD -> Verdict.NSFW
            combined >= SUGGESTIVE_THRESHOLD -> Verdict.SUGGESTIVE
            else -> Verdict.SAFE
        }
        val confidence = when {
            image != null -> 0.9f
            reasons.isNotEmpty() -> 0.8f
            else -> 0.3f
        }
        return Analysis(verdict, combined, confidence, reasons)
    }
}
