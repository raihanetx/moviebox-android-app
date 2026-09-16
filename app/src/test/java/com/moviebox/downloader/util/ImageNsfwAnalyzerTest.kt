package com.moviebox.downloader.util

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ImageNsfwAnalyzerTest {

    @Test
    fun `blank bitmap returns safe`() {
        val bmp = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val score = ImageNsfwAnalyzer.score(bmp)
        assertTrue(score < 0.1f, "white bitmap should be near 0, got $score")
    }

    @Test
    fun `pure skin bitmap scores high`() {
        // Build a 64x64 image entirely of a skin-like RGB value
        val skin = Color.rgb(220, 170, 130)
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(skin)
        val score = ImageNsfwAnalyzer.score(bmp)
        assertTrue(score > 0.5f, "full-skin bitmap should score high, got $score")
    }

    @Test
    fun `small skin patch scores low`() {
        // White background with a 10x10 skin square in the corner of a 64x64 image
        val skin = Color.rgb(220, 170, 130)
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        bmp.setPixel(5, 5, skin)
        val score = ImageNsfwAnalyzer.score(bmp)
        assertTrue(score < 0.3f, "small skin patch should score low, got $score")
    }

    @Test
    fun `large skin blob scores higher than scattered pixels`() {
        val skin = Color.rgb(220, 170, 130)
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        // 20x20 connected block (9% of image)
        for (x in 0 until 20) for (y in 0 until 20) bmp.setPixel(x, y, skin)
        val scoreBlob = ImageNsfwAnalyzer.score(bmp)
        // scattered 40 pixels (0.6%) should be much lower
        val bmp2 = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.WHITE) }
        repeat(40) { bmp2.setPixel(it % 64, it / 64, skin) }
        val scoreScattered = ImageNsfwAnalyzer.score(bmp2)
        assertTrue(scoreBlob > scoreScattered, "blob $scoreBlob should exceed scattered $scoreScattered")
    }
}
