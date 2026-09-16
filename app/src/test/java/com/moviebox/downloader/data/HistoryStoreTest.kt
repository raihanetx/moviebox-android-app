package com.moviebox.downloader.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryStoreTest {

    private lateinit var ctx: Context
    private lateinit var store: HistoryStore

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        store = HistoryStore(ctx)
        runBlocking { store.clear() }
    }

    @After
    fun tearDown() = runBlocking {
        store.clear()
    }

    @Test
    fun `starts empty after clear`() = runBlocking {
        assertEquals(emptyList(), store.entries.first())
    }

    @Test
    fun `add increases size`() = runBlocking {
        val entry = HistoryEntry(
            dmId = 1, title = "Movie", fileName = "movie.mp4", label = "Movie",
            quality = "720p", sizeBytes = 100, cover = "", detailPath = "slug",
            subjectId = "1", se = 0, ep = 0, resolution = 720, mime = "video/mp4",
            createdAt = System.currentTimeMillis(),
        )
        store.add(entry)
        assertEquals(1, store.entries.first().size)
    }

    @Test
    fun `remove deletes entry`() = runBlocking {
        val entry = HistoryEntry(
            dmId = 2, title = "Movie", fileName = "movie.mp4", label = "Movie",
            quality = "720p", sizeBytes = 100, cover = "", detailPath = "slug",
            subjectId = "1", se = 0, ep = 0, resolution = 720, mime = "video/mp4",
            createdAt = System.currentTimeMillis(),
        )
        store.add(entry)
        store.remove(2)
        assertEquals(emptyList(), store.entries.first())
    }

    @Test
    fun `clear empties list`() = runBlocking {
        repeat(3) { i ->
            store.add(
                HistoryEntry(
                    dmId = i.toLong(), title = "Movie $i", fileName = "m$i.mp4", label = "Movie",
                    quality = "720p", sizeBytes = 100, cover = "", detailPath = "slug",
                    subjectId = "1", se = 0, ep = 0, resolution = 720, mime = "video/mp4",
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
        store.clear()
        assertEquals(emptyList(), store.entries.first())
    }
}
