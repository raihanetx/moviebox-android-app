package com.moviebox.downloader.util

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ContentAnalyzerTest {

    @Test
    fun `adult genre yields NSFW verdict`() {
        val a = ContentAnalyzer.analyzeText("Nice Title", "", listOf("Adult"))
        assertEquals(ContentAnalyzer.Verdict.NSFW, a.verdict)
        assertTrue(a.reasons.any { it.contains("genre") })
    }

    @Test
    fun `clean text yields SAFE verdict`() {
        val a = ContentAnalyzer.analyzeText("Moana", "", listOf("Animation"))
        assertEquals(ContentAnalyzer.Verdict.SAFE, a.verdict)
    }

    @Test
    fun `adult keyword in title yields NSFW`() {
        val a = ContentAnalyzer.analyzeText("Big Boobs Movie", "", emptyList())
        assertEquals(ContentAnalyzer.Verdict.NSFW, a.verdict)
    }

    @Test
    fun `image alone can force NSFW`() {
        // image >= IMAGE_NSFW (0.45) must block regardless of text
        val fakeImage = 0.45f
        val a = ContentAnalyzer.build(text = 0f, image = fakeImage, emptyList())
        assertEquals(ContentAnalyzer.Verdict.NSFW, a.verdict)
    }

    @Test
    fun `probabilistic OR boosts combined score`() {
        // text 0.0 + image 0.5 -> combined 0.5
        val a = ContentAnalyzer.build(text = 0f, image = 0.5f, emptyList())
        assertEquals(0.5f, a.score, 0.01f)
    }
}
