package com.moviebox.downloader.util

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContentFilterTest {

    @Test
    fun `adult genre marks result adult`() {
        assertTrue(ContentFilter.isAdultResult("Innocent Title", listOf("Adult")))
        assertTrue(ContentFilter.isAdultResult("Innocent Title", listOf("erotic")))
    }

    @Test
    fun `adult keyword in title blocks result`() {
        assertTrue(ContentFilter.isAdultResult("Big Boobs Movie", emptyList()))
    }

    @Test
    fun `clean title passes cheap filter`() {
        assertTrue(!ContentFilter.isAdultResult("Moana", emptyList()))
        assertTrue(!ContentFilter.isAdultResult("Friends", listOf("Comedy")))
    }

    @Test
    fun `detail check catches keywords in description`() {
        assertTrue(ContentFilter.isAdultDetail("Nice Title", "some plot with sex scene", emptyList()))
    }

    @Test
    fun `query gate blocks adult-intent search`() {
        assertTrue(ContentFilter.isAdultQuery("porn"))
        assertTrue(!ContentFilter.isAdultQuery("comedy"))
    }
}
