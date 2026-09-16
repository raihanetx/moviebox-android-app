package com.moviebox.downloader

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moviebox.downloader.api.MovieBoxApi
import com.moviebox.downloader.data.DownloadRepository
import com.moviebox.downloader.data.DmStatus
import com.moviebox.downloader.data.HistoryEntry
import com.moviebox.downloader.data.HistoryStore
import com.moviebox.downloader.debug.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class DownloadViewModel(
    private val app: Application,
    private val historyStore: HistoryStore,
    private val repo: DownloadRepository,
) : AndroidViewModel(app) {

    val entries: StateFlow<List<HistoryEntry>> = historyStore.entries

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun onMessageShown() {
        _message.value = null
    }

    fun onPermissionDenied() {
        AppLog.warn(AppLog.CAT_UI, "storage permission DENIED")
        _message.value = "Storage permission is needed to save downloads"
    }

    init {
        viewModelScope.launch {
            historyStore.load()
            refreshStatuses()
            startPolling()
        }
    }

    fun downloadSelected(detail: com.moviebox.downloader.api.DetailData, eps: Set<Pair<Int, Int>>, resolution: Int, subtitles: Set<String>) {
        if (eps.isEmpty()) return
        AppLog.i(AppLog.CAT_UI, "batch download: ${eps.size} episode(s) @ ${resolution}p, ${subtitles.size} subtitle(s)")
        viewModelScope.launch {
            var ok = 0
            var failed = 0
            for ((se, ep) in eps.sortedWith(compareBy({ it.first }, { it.second }))) {
                val res = enqueueEpisode(detail, se, ep, resolution, subtitles)
                if (res) ok++ else failed++
                delay(700)
            }
            _message.value = when {
                ok > 0 && failed > 0 -> "$ok download(s) started, $failed failed"
                ok > 0 -> "Started $ok download${if (ok > 1) "s" else ""}"
                else -> "Download failed — try again"
            }
            AppLog.ok(AppLog.CAT_DL, "batch finished: $ok started, $failed failed")
            startPolling()
        }
    }

    fun downloadSingle(detail: com.moviebox.downloader.api.DetailData, se: Int, ep: Int, resolution: Int) {
        AppLog.i(AppLog.CAT_UI, "Get button tapped: S${String.format("%02d", se)} E${String.format("%02d", ep)} @ ${resolution}p")
        viewModelScope.launch {
            val ok = enqueueEpisode(detail, se, ep, resolution, emptySet())
            _message.value = if (ok) "Download started" else "Download failed — try again"
            startPolling()
        }
    }

    private suspend fun enqueueEpisode(
        d: com.moviebox.downloader.api.DetailData,
        se: Int,
        ep: Int,
        resolution: Int,
        subtitles: Set<String>,
    ): Boolean {
        AppLog.i(AppLog.CAT_DL, "resolving signed link for episode…")
        return try {
            val links = MovieBoxApi.fetchLinks(d.detailPath, d.subjectId, se, ep)
            val stream = links.streams.firstOrNull { it.resolutionInt == resolution }
                ?: links.streams.lastOrNull()
                ?: throw IllegalStateException("no stream available")
            val fileName = "${d.title} ${seasonEp(se, ep)} [${stream.resolutionInt}]p.mp4"
            val res = repo.enqueue(stream.url, fileName, "video/mp4")
            val dmId = res.dmId
            historyStore.add(
                HistoryEntry(
                    dmId = dmId,
                    title = d.title,
                    fileName = fileName,
                    label = episodeLabel(d, se, ep),
                    quality = "${stream.resolutionInt}p",
                    sizeBytes = stream.sizeBytes,
                    cover = d.cover,
                    detailPath = d.detailPath,
                    subjectId = d.subjectId,
                    se = se,
                    ep = ep,
                    resolution = stream.resolutionInt,
                    mime = "video/mp4",
                    createdAt = System.currentTimeMillis(),
                    total = stream.sizeBytes,
                    needsImport = res.needsImport,
                )
            )
            AppLog.ok(AppLog.CAT_DL, "\"$fileName\" is now downloading (id #$dmId)")
            for (lan in subtitles) {
                val cap = links.captions.firstOrNull { it.lanName == lan } ?: continue
                try {
                    val srtName = fileName.removeSuffix(".mp4") + " - ${cap.lanName}.srt"
                    val capRes = repo.enqueue(cap.url, srtName, "application/octet-stream")
                    historyStore.add(
                        HistoryEntry(
                            dmId = capRes.dmId,
                            title = d.title,
                            fileName = srtName,
                            label = "${episodeLabel(d, se, ep)} · subtitle",
                            quality = cap.lanName,
                            sizeBytes = 0,
                            cover = "",
                            detailPath = d.detailPath,
                            subjectId = d.subjectId,
                            se = se,
                            ep = ep,
                            resolution = stream.resolutionInt,
                            mime = "application/octet-stream",
                            isSubtitle = true,
                            createdAt = System.currentTimeMillis(),
                            needsImport = capRes.needsImport,
                        )
                    )
                    AppLog.ok(AppLog.CAT_DL, "subtitle queued: \"$srtName\" (id #${capRes.dmId})")
                } catch (_: Exception) {
                }
            }
            true
        } catch (e: Exception) {
            AppLog.e(AppLog.CAT_DL, "could NOT start download: ${friendlyError(e)}")
            false
        }
    }

    fun redownload(entry: HistoryEntry) {
        AppLog.i(AppLog.CAT_UI, "re-download requested: \"${entry.fileName}\"")
        viewModelScope.launch {
            try {
                val links = MovieBoxApi.fetchLinks(entry.detailPath, entry.subjectId, entry.se, entry.ep)
                val stream = links.streams.firstOrNull { it.resolutionInt == entry.resolution }
                    ?: links.streams.lastOrNull()
                    ?: throw IllegalStateException("no stream available")
                val res = repo.enqueue(stream.url, entry.fileName, entry.mime)
                historyStore.replace(
                    entry.dmId,
                    entry.copy(
                        dmId = res.dmId,
                        status = "queued",
                        soFar = 0,
                        total = stream.sizeBytes,
                        sizeBytes = stream.sizeBytes,
                        localUri = null,
                        createdAt = System.currentTimeMillis(),
                        needsImport = res.needsImport,
                    )
                )
                _message.value = "Re-downloading ${entry.fileName}"
                AppLog.ok(AppLog.CAT_DL, "re-download queued with fresh link (id #${res.dmId})")
                startPolling()
            } catch (e: Exception) {
                _message.value = "Re-download failed: ${friendlyError(e)}"
                AppLog.e(AppLog.CAT_DL, "re-download FAILED: ${friendlyError(e)}")
            }
        }
    }

    fun cancelEntry(entry: HistoryEntry) {
        AppLog.warn(AppLog.CAT_UI, "cancel tapped: \"${entry.fileName}\"")
        repo.cancel(entry.dmId)
        viewModelScope.launch {
            historyStore.updateWhere(entry.dmId) { it.copy(status = "canceled") }
        }
    }

    fun removeEntry(entry: HistoryEntry, deleteFile: Boolean) {
        AppLog.warn(AppLog.CAT_UI, "remove tapped: \"${entry.fileName}\"${if (deleteFile) " — file will be deleted" else ""}")
        repo.cancel(entry.dmId)
        if (deleteFile) entry.localUri?.let { repo.deleteFile(it) }
        viewModelScope.launch {
            historyStore.remove(entry.dmId)
        }
    }

    fun openEntry(entry: HistoryEntry): Boolean {
        val uri = entry.localUri ?: return false
        val ok = repo.openFile(uri, entry.mime)
        AppLog.i(AppLog.CAT_UI, if (ok) "opening \"${entry.fileName}\"" else "could not open \"${entry.fileName}\"")
        return ok
    }

    fun clearHistory() {
        AppLog.warn(AppLog.CAT_UI, "clear-all history tapped")
        viewModelScope.launch {
            for (e in historyStore.entries.value) {
                if (e.status == "running" || e.status == "queued") repo.cancel(e.dmId)
            }
            historyStore.clear()
            _message.value = "Download list cleared"
        }
    }

    fun openDownloadsApp() {
        repo.openDownloadsApp()
    }

    /* ---------- progress polling ---------- */

    private var pollJob: kotlinx.coroutines.Job? = null
    private val loggedPercent = HashMap<Long, Int>()

    private suspend fun refreshStatuses() {
        for (e in historyStore.entries.value) {
            if (e.status == "completed" && e.needsImport && e.localUri != null) {
                importStagedFile(e)
                continue
            }
            if (e.status != "queued" && e.status != "running") continue
            val st = repo.query(e.dmId) ?: continue
            when (st.status) {
                DownloadManager.STATUS_RUNNING -> {
                    historyStore.updateWhere(e.dmId) { it.copy(status = "running", soFar = st.soFar, total = st.total) }
                    logProgress(e, st.soFar, st.total)
                }
                DownloadManager.STATUS_SUCCESSFUL -> {
                    if (e.needsImport) {
                        val staged = st.localUri
                        val finalUri = staged?.let { repo.importToMediaStore(it, e.fileName, e.mime) }
                        historyStore.updateWhere(e.dmId) {
                            it.copy(status = "completed", soFar = st.total, total = st.total, localUri = finalUri, needsImport = false)
                        }
                        logCompleted(e, st, finalUri)
                    } else {
                        historyStore.updateWhere(e.dmId) { it.copy(status = "completed", soFar = st.total, total = st.total, localUri = st.localUri ?: it.localUri) }
                        logCompleted(e, st, st.localUri)
                    }
                }
                DownloadManager.STATUS_FAILED -> {
                    historyStore.updateWhere(e.dmId) { it.copy(status = "failed") }
                    AppLog.e(AppLog.CAT_DL, "download FAILED: \"${e.fileName}\" — ${dmReasonText(st.reason)}")
                }
                DownloadManager.STATUS_PAUSED -> {
                    historyStore.updateWhere(e.dmId) { it.copy(status = "paused", soFar = st.soFar, total = st.total) }
                    AppLog.warn(AppLog.CAT_DL, "download paused: \"${e.fileName}\"")
                }
            }
        }
    }

    private fun logProgress(e: HistoryEntry, soFar: Long, total: Long) {
        if (total <= 0) return
        val pct = ((soFar * 100) / total).toInt()
        val last = loggedPercent[e.dmId] ?: 0
        if (pct - last >= 10 || (pct >= 100 && last < 100)) {
            loggedPercent[e.dmId] = pct
            AppLog.i(AppLog.CAT_DL, "progress ${pct}%: \"${e.fileName}\" (${fmtMB(soFar)} / ${fmtMB(total)})")
        }
    }

    private suspend fun importStagedFile(e: HistoryEntry) {
        val staged = e.localUri ?: return
        AppLog.i(AppLog.CAT_DL, "found \"${e.fileName}\" still waiting to be moved into public Downloads — doing that now…")
        val finalUri = repo.importToMediaStore(staged, e.fileName, e.mime)
        if (finalUri != null) {
            historyStore.updateWhere(e.dmId) { it.copy(localUri = finalUri, needsImport = false) }
        } else {
            historyStore.updateWhere(e.dmId) { it.copy(needsImport = false) }
        }
    }

    private fun logCompleted(e: HistoryEntry, st: DmStatus, finalUri: String?) {
        if (e.status == "completed") return
        loggedPercent.remove(e.dmId)
        val secs = (System.currentTimeMillis() - e.createdAt) / 1000.0
        val speed = if (secs > 1) st.total / secs / (1024.0 * 1024.0) else 0.0
        AppLog.ok(
            AppLog.CAT_DL,
            "download COMPLETED: \"${e.fileName}\" (${fmtMB(st.total)}" +
                (if (secs > 3) ", took ${"%.0f".format(secs)} s at ${"%.1f".format(speed)} MB/s avg" else "") + ")",
            "saved to: ${finalUri ?: "Downloads/MovieBox (exact location unknown)"}",
        )
    }

    private fun fmtMB(b: Long): String = "%.1f MB".format(b / 1048576.0)

    private fun dmReasonText(r: Int): String = when (r) {
        DownloadManager.ERROR_UNKNOWN -> "unknown error"
        DownloadManager.ERROR_FILE_ERROR -> "file/storage error"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "CDN returned an unexpected HTTP code"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "transfer failed mid-way"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "too many redirects"
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "insufficient storage space"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "external storage/sdcard not found"
        DownloadManager.ERROR_CANNOT_RESUME -> "cannot resume the partial download"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "file already exists"
        else -> "reason code $r"
    }

    fun startPolling() {
        if (pollJob?.isActive == true) return
        AppLog.i(AppLog.CAT_LIFE, "download status poller started")
        pollJob = viewModelScope.launch {
            while (true) {
                val active = historyStore.entries.value.count { it.status == "queued" || it.status == "running" }
                if (active == 0) {
                    refreshStatuses()
                    break
                }
                refreshStatuses()
                delay(1000)
            }
            AppLog.i(AppLog.CAT_LIFE, "download status poller stopped — no active downloads")
        }
    }

    /* ---------- helpers ---------- */

    private fun episodeLabel(d: com.moviebox.downloader.api.DetailData, se: Int, ep: Int): String = when (d.content) {
        is com.moviebox.downloader.api.ContentLayout.Episodes -> "S${String.format("%02d", se)} E${String.format("%02d", ep)}"
        is com.moviebox.downloader.api.ContentLayout.Parts -> "Part $se"
    }

    private fun seasonEp(se: Int, ep: Int): String =
        "S${String.format("%02d", se)} E${String.format("%02d", ep)}"

    private fun friendlyError(e: Exception): String = when {
        e.message?.contains("401") == true -> "Session expired — please retry"
        e.message?.contains("empty response") == true -> "No results — the site may have changed"
        else -> e.message ?: "Unknown error"
    }
}
