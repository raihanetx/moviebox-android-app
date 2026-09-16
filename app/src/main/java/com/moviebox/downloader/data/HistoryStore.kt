package com.moviebox.downloader.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.moviebox.downloader.debug.AppLog
import java.io.File

@Serializable
data class HistoryEntry(
    val dmId: Long,
    val title: String,        // content title (e.g. "Friends S10")
    val fileName: String,     // saved file name
    val label: String,        // "S01 E05" | "Movie" | "Part 2"
    val quality: String,      // "480p"
    val sizeBytes: Long,
    val cover: String,
    val detailPath: String,
    val subjectId: String,
    val se: Int,
    val ep: Int,
    val resolution: Int,
    val mime: String,
    val isSubtitle: Boolean = false,
    val createdAt: Long,
    var status: String = "queued",   // queued | running | paused | completed | failed | canceled
    var soFar: Long = 0,
    var total: Long = 0,
    var localUri: String? = null,
    /** true while the finished file still sits in the app's private staging
     * folder and still has to be moved into public Downloads/MovieBox. */
    var needsImport: Boolean = false,
)

/** Simple JSON-file backed store — no annotation processing, no Room. */
class HistoryStore(context: Context) {

    private val file: File = File(context.filesDir, "history.json")
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
    private val mutex = Mutex()

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries

    suspend fun load() = mutex.withLock {
        val t0 = System.currentTimeMillis()
        val list = withContext(Dispatchers.IO) {
            if (file.exists()) {
                try {
                    json.decodeFromString<List<HistoryEntry>>(file.readText())
                } catch (_: Exception) {
                    null
                }
            } else null
        }
        if (list == null) {
            if (file.exists()) {
                AppLog.warn(
                    AppLog.CAT_STORE,
                    "history.json exists but could not be parsed — starting with an empty download list",
                )
            } else {
                AppLog.i(AppLog.CAT_STORE, "no saved history.json — this is a fresh install (empty download list)")
            }
        } else {
            AppLog.ok(
                AppLog.CAT_STORE,
                "download history restored: ${list.size} entr${if (list.size == 1) "y" else "ies"} (${file.length() / 1024} KB, ${System.currentTimeMillis() - t0} ms)",
                list.take(10).joinToString("\n") { "· ${it.fileName} [${it.status}]" },
            )
        }
        _entries.value = list ?: emptyList()
    }

    private suspend fun persist(list: List<HistoryEntry>) = withContext(Dispatchers.IO) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.writeText(json.encodeToString(list))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            AppLog.e(AppLog.CAT_STORE, "failed to save history.json — ${e.javaClass.simpleName}: ${e.message ?: ""}")
            tmp.delete()
        }
    }

    suspend fun add(entry: HistoryEntry) = mutex.withLock {
        // a re-download replaces the old entry
        val list = _entries.value.filter { it.dmId != entry.dmId } + entry
        _entries.value = list
        persist(list)
        AppLog.i(AppLog.CAT_STORE, "history saved: \"${entry.fileName}\" (${list.size} items total)")
    }

    suspend fun remove(dmId: Long) = mutex.withLock {
        val list = _entries.value.filter { it.dmId != dmId }
        _entries.value = list
        persist(list)
    }

    suspend fun replace(oldDmId: Long, entry: HistoryEntry) = mutex.withLock {
        val list = _entries.value.filter { it.dmId != oldDmId } + entry
        _entries.value = list
        persist(list)
    }

    suspend fun updateWhere(dmId: Long, mutate: (HistoryEntry) -> HistoryEntry) = mutex.withLock {
        val list = _entries.value.map { if (it.dmId == dmId) mutate(it) else it }
        _entries.value = list
        persist(list)
    }

    suspend fun clear() = mutex.withLock {
        _entries.value = emptyList()
        persist(emptyList())
        AppLog.warn(AppLog.CAT_STORE, "download history cleared (files on disk are kept unless deleted per item)")
    }
}
