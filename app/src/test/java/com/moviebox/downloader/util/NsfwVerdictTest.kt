package com.moviebox.downloader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the wall-2 verdict logic and soft-signal detection.
 * (The TFLite model itself can't run on the JVM — it was verified with real
 * site covers via the Python harness in scripts/test_model_real_covers.py.)
 */
class NsfwVerdictTest {

    private fun scores(
        drawings: Float = 0f, hentai: Float = 0f, neutral: Float = 1f,
        porn: Float = 0f, sexy: Float = 0f,
    ) = NsfwImageClassifier.Scores(drawings, hentai, neutral, porn, sexy)

    /* ---------- threshold rules (STRICT profile) ---------- */

    @Test fun `neutral poster passes`() {
        val v = NsfwImageClassifier.verdict(scores(neutral = 0.95f), softTextSignal = false)
        assertFalse(v.isNsfw)
    }

    @Test fun `anime art passes even at 100 percent drawings`() {
        val v = NsfwImageClassifier.verdict(scores(drawings = 1f), softTextSignal = false)
        assertFalse(v.isNsfw)
    }

    @Test fun `porn above threshold blocks`() {
        val v = NsfwImageClassifier.verdict(scores(porn = 0.42f), softTextSignal = false)
        assertTrue(v.isNsfw)
        assertTrue(v.reason!!.contains("porn"))
    }

    @Test fun `porn just below threshold passes`() {
        val v = NsfwImageClassifier.verdict(scores(porn = 0.39f), softTextSignal = false)
        assertFalse(v.isNsfw)
    }

    @Test fun `hentai above threshold blocks`() {
        val v = NsfwImageClassifier.verdict(scores(hentai = 0.55f), softTextSignal = false)
        assertTrue(v.isNsfw)
        assertTrue(v.reason!!.contains("hentai"))
    }

    @Test fun `sexy moderate alone passes`() {
        // normal movie-poster range — attractive cast, swimwear etc.
        val v = NsfwImageClassifier.verdict(scores(sexy = 0.60f), softTextSignal = false)
        assertFalse(v.isNsfw)
    }

    @Test fun `sexy very high alone blocks`() {
        val v = NsfwImageClassifier.verdict(scores(sexy = 0.85f), softTextSignal = false)
        assertTrue(v.isNsfw)
    }

    @Test fun `sexy moderate with soft title blocks - combined vote`() {
        val v = NsfwImageClassifier.verdict(scores(sexy = 0.60f), softTextSignal = true)
        assertTrue(v.isNsfw)
        assertTrue(v.reason!!.contains("suggestive"))
    }

    @Test fun `sexy low with soft title passes`() {
        val v = NsfwImageClassifier.verdict(scores(sexy = 0.50f), softTextSignal = true)
        assertFalse(v.isNsfw)
    }

    @Test fun `null scores fail open`() {
        val v = NsfwImageClassifier.verdict(null, softTextSignal = true)
        assertFalse(v.isNsfw)
    }

    /* ---------- soft signal detection (ContentFilter) ---------- */

    @Test fun `clean title has no soft signal`() {
        assertFalse(ContentFilter.hasSoftSignal("Weak Hero Season 2"))
        assertFalse(ContentFilter.hasSoftSignal("Friends"))
        assertFalse(ContentFilter.hasSoftSignal("One Piece"))
    }

    @Test fun `romance vocabulary has soft signal`() {
        assertTrue(ContentFilter.hasSoftSignal("Midnight Desire"))
        assertTrue(ContentFilter.hasSoftSignal("A Passionate Love"))
        assertTrue(ContentFilter.hasSoftSignal("Temptation of the Heart"))
        assertTrue(ContentFilter.hasSoftSignal("Forbidden Love"))
    }

    @Test fun `soft signal is case and punctuation proof`() {
        assertTrue(ContentFilter.hasSoftSignal("MIDNIGHT DESIRE!"))
        assertTrue(ContentFilter.hasSoftSignal("temptation-of-the-heart"))
    }

    /* ---------- regression: wall 1 still intact ---------- */

    @Test fun `wall1 adult queries still blocked`() {
        assertTrue(ContentFilter.isAdultQuery("hentai"))
        assertTrue(ContentFilter.isAdultQuery("p0rn"))
        assertTrue(ContentFilter.isAdultQuery("hantai"))   // fuzzy
        assertTrue(ContentFilter.isAdultResult("Yoasobi Gurashi", listOf()))
        assertFalse(ContentFilter.isAdultQuery("weak hero"))
        assertFalse(ContentFilter.isAdultQuery("one piece"))
    }
}
