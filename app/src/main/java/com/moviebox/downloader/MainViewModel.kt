package com.moviebox.downloader

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moviebox.downloader.api.ContentLayout
import com.moviebox.downloader.api.MovieBoxApi
import com.moviebox.downloader.data.DownloadRepository
import com.moviebox.downloader.data.HistoryEntry
import com.moviebox.downloader.data.HistoryStore
import com.moviebox.downloader.debug.AppLog
import com.moviebox.downloader.util.ContentFilter
import com.moviebox.downloader.util.NsfwImageClassifier
import android.content.Context
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    /* ---- navigation (cross-cutting, kept here) ---- */

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set
    private val backStack = ArrayDeque<Screen>()
    val canGoBack: Boolean get() = backStack.isNotEmpty()

    fun openDetail(slug: String) {
        if (screen is Screen.Detail && (screen as Screen.Detail).slug == slug) return
        AppLog.i(AppLog.CAT_NAV, "open detail page: $slug")
        backStack.addLast(screen)
        screen = Screen.Detail(slug)
        lastSlug = slug
        loadDetail(slug)
    }

    fun goBack(): Boolean {
        if (backStack.isEmpty()) return false
        screen = backStack.removeLast()
        AppLog.i(AppLog.CAT_NAV, "back navigation → ${screenName(screen)}")
        return true
    }

    fun navigateHome() {
        AppLog.i(AppLog.CAT_NAV, "navigate → Home")
        backStack.clear()
        screen = Screen.Home
    }

    fun navigateDownloads() {
        AppLog.i(AppLog.CAT_NAV, "navigate → Downloads")
        backStack.clear()
        screen = Screen.Downloads
    }

    fun openDebug() {
        if (screen is Screen.Debug) return
        AppLog.i(AppLog.CAT_NAV, "open Debug screen")
        backStack.addLast(screen)
        screen = Screen.Debug
    }

    private fun screenName(s: Screen): String = when (s) {
        is Screen.Home -> "Home"
        is Screen.Detail -> "Detail(${s.slug})"
        is Screen.Downloads -> "Downloads"
        is Screen.Debug -> "Debug"
    }

    /* ---- search / detail state (proxied from SearchLogic) ---- */

    var query by mutableStateOf("")

    private val _homeUi = mutableStateOf(HomeUiState())
    val homeUi: HomeUiState get() = _homeUi.value

    private val _detailUi = mutableStateOf(DetailUiState())
    val detailUi: DetailUiState get() = _detailUi.value

    val recentQueries: StateFlow<List<String>> get() = searchLogic.recentQueries

    var safeSearch: Boolean
        get() = prefs.getBoolean("safe_search", true)
        set(value) {
            prefs.edit().putBoolean("safe_search", value).apply()
            searchLogic.safeSearch = value
            AppLog.i(AppLog.CAT_UI, "SafeSearch toggled ${if (value) "ON" else "OFF"}")
            if (value) {
                com.moviebox.downloader.util.NsfwDetector.clearCache()
                searchLogic.verifiedAdult.clear()
            }
            val ui = _homeUi.value
            if (ui.searched && searchLogic.lastRawResults.isNotEmpty()) {
                val (kept, blocked) = searchLogic.applyFilter(searchLogic.lastRawResults)
                _detailUi.value = _detailUi.value.copy(selectedEps = emptySet())
                _homeUi.value = ui.copy(results = kept, blockedCount = blocked.size)
            }
        }

    private val prefs = app.getSharedPreferences("moviebox_settings", Context.MODE_PRIVATE)

    /* ---- domain logic delegates ---- */

    private val searchLogic = SearchLogic(app)
    private val downloadLogic = DownloadLogic(
        app = app,
        historyStore = HistoryStore(app),
        repo = DownloadRepository(app),
        scope = viewModelScope,
    )
    private val debugLogic = DebugLogic(app)

    val entries: StateFlow<List<HistoryEntry>> get() = downloadLogic.historyStoreEntries
    val message: StateFlow<String?> get() = downloadLogic.message
    val debugUi: StateFlow<DebugUiState> get() = debugLogic.debugUi

    fun onMessageShown() = downloadLogic.onMessageShown()

    fun toggleSafeSearch() {
        safeSearch = !safeSearch
    }

    fun removeRecentQuery(q: String) {
        searchLogic.removeRecentQuery(q)
    }

    /**
     * Fetch each survivor's detail page and run the full isAdultDetail check
     * (title + description + genres) PLUS the wall-2 NSFW image check on the
     * cover poster, so blocked posters never render.
     * Bounded concurrency (6), per-item timeout, fail-open on error: a network
     * problem must never hide legit content.
     */
    private suspend fun verifySafe(survivors: List<SearchResult>): List<SearchResult> {
        if (survivors.isEmpty()) return survivors
        // one-time model load off the critical path of the first item
        NsfwImageClassifier.ensureLoaded(getApplication())
        val semaphore = Semaphore(6)
        return coroutineScope {
            survivors.map { item ->
                async {
                    semaphore.withPermit {
                        val cached = verifiedAdult[item.detailPath]
                        if (cached != null) {
                            return@withPermit if (cached) null else item
                        }
                        val verdict = try {
                            withTimeoutOrNull(12_000) {
                                val d = MovieBoxApi.fetchDetail(item.detailPath)
                                val textHit = ContentFilter.isAdultDetail(d.title, d.description, d.genres)
                                val imgHit = if (textHit) true else {
                                    // wall 2 — classify the poster itself
                                    NsfwImageClassifier.classifyCoverBlocking(
                                        getApplication(), item.cover, item.title,
                                    ).isNsfw
                                }
                                textHit || imgHit
                            }
                        } catch (e: Exception) {
                            AppLog.warn(
                                AppLog.CAT_NET,
                                "SafeSearch verify failed for \"${item.title}\"",
                                e.message ?: ""
                            )
                            null // fail-open
                        }
                        when (verdict) {
                            null -> item // inconclusive (timeout/error) — keep
                            else -> {
                                verifiedAdult[item.detailPath] = verdict
                                if (verdict) {
                                    AppLog.i(
                                        AppLog.CAT_UI,
                                        "SafeSearch hid \"${item.title}\" after detail verification"
                                    )
                                    null
                                } else item
                            }
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private val _homeUi = MutableStateFlow(HomeUiState())
    val homeUi: StateFlow<HomeUiState> = _homeUi.asStateFlow()

    fun search() {
        val input = query.trim()
        if (input.isEmpty()) return
        searchLogic.query = input
        _homeUi.value = HomeUiState(loading = true)
        viewModelScope.launch {
            try {
                val fetched = MovieBoxApi.search(input)
                // The upstream API sometimes returns the exact same row twice
                // (aggregated sources). LazyVerticalGrid keys items by
                // detailPath, so a duplicate crashes the app with
                // "Key ... was already used" — dedupe before anything else.
                val raw = fetched.distinctBy { it.detailPath }
                if (fetched.size != raw.size) {
                    AppLog.warn(
                        AppLog.CAT_NET,
                        "search returned ${fetched.size - raw.size} duplicate row(s) — dropped"
                    )
                }
                lastRawResults = raw
                val (cheapKept, cheapBlocked) = applyFilter(raw)
                // SafeSearch v3.2: verify survivors against their detail pages
                // BEFORE rendering, so blocked posters never appear in the grid.
                val results = if (safeSearch) verifySafe(cheapKept) else cheapKept
                val blockedCount = raw.size - results.size
                if (blockedCount > 0) {
                    AppLog.i(
                        AppLog.CAT_UI,
                        "SafeSearch hid $blockedCount adult result(s) (cheap: ${cheapBlocked.size}, detail-verified: ${blockedCount - cheapBlocked.size})"
                    )
                }
                AppLog.ok(AppLog.CAT_NET, "search complete: ${results.size} shown / ${raw.size} total for \"$input\"")
                if (results.isNotEmpty()) saveRecentQuery(input)
                _homeUi.value = HomeUiState(loading = false, results = results, searched = true, blockedCount = blockedCount)
            } catch (e: Exception) {
                AppLog.e(AppLog.CAT_NET, "search FAILED: ${friendlyError(e)}")
                _homeUi.value = HomeUiState(loading = false, error = friendlyError(e))
            }
        }
    }

    private var lastSlug: String? = null

    private fun loadDetail(slug: String) {
        if (slug == lastSlug && _detailUi.value.detail != null) {
            AppLog.i(AppLog.CAT_NAV, "detail for \"$slug\" already loaded")
            return
        }
        lastSlug = slug
        _detailUi.value = DetailUiState(loading = true)
        viewModelScope.launch {
            try {
                val d = MovieBoxApi.fetchDetail(slug)
                if (safeSearch && ContentFilter.isAdultDetail(d.title, d.description, d.genres)) {
                    AppLog.i(AppLog.CAT_UI, "SafeSearch blocked detail page \"${d.title}\" (adult keywords in title/description/genres)")
                    _detailUi.value = DetailUiState(
                        loading = false,
                        error = "Blocked by SafeSearch — this title looks like adult content.\n\n" +
                            "Turn SafeSearch off on the search screen if you want to open it anyway.",
                    )
                    return@launch
                }
                // Wall 2 — the poster itself (direct-link visits skip search's
                // verifySafe, so the hero image is checked here too).
                if (safeSearch && NsfwImageClassifier.classifyCoverBlocking(
                        getApplication(), d.cover, d.title,
                    ).isNsfw
                ) {
                    AppLog.i(AppLog.CAT_UI, "SafeSearch blocked detail page \"${d.title}\" (NSFW poster image)")
                    _detailUi.value = DetailUiState(
                        loading = false,
                        error = "Blocked by SafeSearch — this title's cover image looks like adult content.\n\n" +
                            "Turn SafeSearch off on the search screen if you want to open it anyway.",
                    )
                    return@launch
                }
                val initialSe = when (val c = d.content) {
                    is ContentLayout.Episodes -> c.seasons.firstOrNull()?.se ?: 0
                    is ContentLayout.Parts -> c.items.firstOrNull()?.se ?: 0
                }
                _detailUi.value = DetailUiState(loading = false, detail = d, selectedSe = initialSe)
                refreshLinks()
            } catch (e: Exception) {
                AppLog.e(AppLog.CAT_NET, "detail load FAILED for \"$slug\": ${friendlyError(e)}")
                _detailUi.value = DetailUiState(loading = false, error = friendlyError(e))
            }
        }
    }

    fun switchDub(detailPath: String) {
        if (detailPath.isEmpty()) return
        val slug = detailPath.substringAfterLast('/')
        AppLog.i(AppLog.CAT_UI, "dub switched → \"$slug\"")
        lastSlug = null
        if (screen is Screen.Detail) screen = Screen.Detail(slug)
        loadDetail(slug)
    }

    fun retryLinks() = refreshLinks()

    fun selectSeason(se: Int) {
        val ui = _detailUi.value
        if (ui.selectedSe == se) return
        _detailUi.value = ui.copy(selectedSe = se, selectedEps = emptySet())
        refreshLinks()
    }

    fun selectResolution(res: Int) {
        if (_detailUi.value.selectedResolution == res) return
        _detailUi.value = _detailUi.value.copy(selectedResolution = res)
    }

    fun toggleSubtitle(lan: String) {
        val cur = _detailUi.value.selectedSubtitles
        val next = if (lan in cur) cur - lan else cur + lan
        _detailUi.value = _detailUi.value.copy(selectedSubtitles = next)
    }

    fun toggleEp(se: Int, ep: Int) {
        val key = se to ep
        val cur = _detailUi.value.selectedEps
        val next = if (key in cur) cur - key else cur + key
        _detailUi.value = _detailUi.value.copy(selectedEps = next)
    }

    fun selectAllEps() {
        val d = _detailUi.value.detail ?: return
        val all = when (val c = d.content) {
            is ContentLayout.Episodes -> {
                val season = c.seasons.firstOrNull { it.se == _detailUi.value.selectedSe }
                (1..(season?.maxEp ?: 0)).map { _detailUi.value.selectedSe to it }.toSet()
            }
            is ContentLayout.Parts -> c.items.map { it.se to it.ep }.toSet()
            else -> emptySet()
        }
        _detailUi.value = _detailUi.value.copy(selectedEps = all)
    }

    fun clearSelection() {
        _detailUi.value = _detailUi.value.copy(selectedEps = emptySet())
    }

    private fun refreshLinks() {
        val d = _detailUi.value.detail ?: return
        val se = _detailUi.value.selectedSe
        viewModelScope.launch {
            searchLogic.refreshLinks(
                detail = d,
                selectedSe = se,
                selectedResolution = _detailUi.value.selectedResolution,
                selectedSubtitles = _detailUi.value.selectedSubtitles,
                onUpdated = { links, res, subs ->
                    val available = links.streams.map { it.resolutionInt }
                    _detailUi.value = _detailUi.value.copy(
                        streams = links.streams,
                        captions = links.captions,
                        linksLoading = false,
                        selectedResolution = res,
                        selectedSubtitles = subs,
                    )
                },
                onError = { error -> _detailUi.value = _detailUi.value.copy(linksLoading = false, linksError = error) },
            )
        }
    }

    fun downloadSelected() {
        val d = _detailUi.value.detail ?: return
        val eps = _detailUi.value.selectedEps.sortedWith(compareBy({ it.first }, { it.second }))
        downloadLogic.downloadSelected(d, eps.toSet(), _detailUi.value.selectedResolution, _detailUi.value.selectedSubtitles)
        _detailUi.value = _detailUi.value.copy(selectedEps = emptySet())
    }

    fun downloadSingle(se: Int, ep: Int) {
        val d = _detailUi.value.detail ?: return
        downloadLogic.downloadSingle(d, se, ep, _detailUi.value.selectedResolution)
    }

    fun onPermissionDenied() {
        AppLog.warn(AppLog.CAT_UI, "storage permission denied", "")
    }

    fun redownload(entry: HistoryEntry) {
        AppLog.i(AppLog.CAT_UI, "re-download requested from history: \"${entry.fileName}\"")
        viewModelScope.launch {
            try {
                val links = MovieBoxApi.fetchLinks(entry.detailPath, entry.subjectId, entry.se, entry.ep)
                val stream = pickStream(links.streams, entry.resolution)
                    ?: throw IllegalStateException("no stream available")
                val res = repo.enqueue(stream.url, entry.fileName, entry.mime)
                history.replace(
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
                AppLog.ok(AppLog.CAT_DL, "re-download queued with a fresh signed link (id #${res.dmId})")
                startPolling()
            } catch (e: Exception) {
                _message.value = "Re-download failed: ${friendlyError(e)}"
                AppLog.e(AppLog.CAT_DL, "re-download FAILED: ${friendlyError(e)}")
            }
        }
    }

    fun cancelEntry(entry: HistoryEntry) {
        AppLog.warn(AppLog.CAT_UI, "cancel tapped in history: \"${entry.fileName}\"")
        repo.cancel(entry.dmId)
        viewModelScope.launch {
            history.updateWhere(entry.dmId) { it.copy(status = "canceled") }
        }
    }

    fun removeEntry(entry: HistoryEntry, deleteFile: Boolean) {
        AppLog.warn(
            AppLog.CAT_UI,
            "remove tapped in history: \"${entry.fileName}\"${if (deleteFile) " — the saved file will be deleted too" else ""}",
        )
        repo.cancel(entry.dmId)
        if (deleteFile) entry.localUri?.let { repo.deleteFile(it) }
        viewModelScope.launch {
            history.remove(entry.dmId)
        }
    }

    fun openEntry(entry: HistoryEntry): Boolean {
        val uri = entry.localUri ?: return false
        val ok = repo.openFile(uri, entry.mime)
        AppLog.i(
            AppLog.CAT_UI,
            if (ok) "opening \"${entry.fileName}\" with the default video player"
            else "could not open \"${entry.fileName}\" — no player app found or the file is gone",
        )
        return ok
    }

    fun clearHistory() {
        AppLog.warn(AppLog.CAT_UI, "clear-all history tapped — canceling active downloads and wiping the list")
        viewModelScope.launch {
            // cancel running + remove files, then wipe the list
            for (e in history.entries.value) {
                if (e.status == "running" || e.status == "queued") repo.cancel(e.dmId)
            }
            history.clear()
            _message.value = "Download list cleared"
        }
    }

    fun openDownloadsApp() {
        repo.openDownloadsApp()
    }

    /* ---------------------------------------------------------------- */
    /* Progress polling                                                  */
    /* ---------------------------------------------------------------- */

    private var pollJob: Job? = null
    private val loggedPercent = HashMap<Long, Int>()

    private suspend fun refreshStatuses() {
        for (e in history.entries.value) {
            // finished earlier (or app was killed mid-import): move the staged
            // file into the public Downloads folder now
            if (e.status == "completed" && e.needsImport && e.localUri != null) {
                importStagedFile(e)
                continue
            }
            if (e.status != "queued" && e.status != "running") continue
            val st = repo.query(e.dmId) ?: continue
            when (st.status) {
                DownloadManager.STATUS_RUNNING -> {
                    history.updateWhere(e.dmId) { it.copy(status = "running", soFar = st.soFar, total = st.total) }
                    logProgress(e, st.soFar, st.total)
                }
                DownloadManager.STATUS_SUCCESSFUL -> {
                    if (e.needsImport) {
                        // strategy C: the file sits in the app's staging folder —
                        // move it into public Downloads/MovieBox now
                        val staged = st.localUri
                        val finalUri = staged?.let { repo.importToMediaStore(it, e.fileName, e.mime) }
                        if (finalUri != null) {
                            history.updateWhere(e.dmId) {
                                it.copy(status = "completed", soFar = st.total, total = st.total, localUri = finalUri, needsImport = false)
                            }
                            logCompleted(e, st, finalUri)
                        } else {
                            // move failed — keep the file where it is, stop retrying
                            history.updateWhere(e.dmId) {
                                it.copy(status = "completed", soFar = st.total, total = st.total, localUri = staged ?: it.localUri, needsImport = false)
                            }
                            logCompleted(e, st, staged)
                        }
                    } else {
                        history.updateWhere(e.dmId) { it.copy(status = "completed", soFar = st.total, total = st.total, localUri = st.localUri ?: it.localUri) }
                        logCompleted(e, st, st.localUri)
                    }
                }
                DownloadManager.STATUS_FAILED -> {
                    history.updateWhere(e.dmId) { it.copy(status = "failed") }
                    AppLog.e(
                        AppLog.CAT_DL,
                        "download FAILED: \"${e.fileName}\" — ${dmReasonText(st.reason)}",
                        "DownloadManager id #${e.dmId} · reason code ${st.reason}",
                    )
                }
                DownloadManager.STATUS_PAUSED -> {
                    history.updateWhere(e.dmId) { it.copy(status = "paused", soFar = st.soFar, total = st.total) }
                    AppLog.warn(AppLog.CAT_DL, "download paused: \"${e.fileName}\" — ${dmPauseText(st.reason)}")
                }
            }
        }
    }

    /** Logs every crossed 10% milestone so progress is visible without spam. */
    private fun logProgress(e: HistoryEntry, soFar: Long, total: Long) {
        if (total <= 0) return
        val pct = ((soFar * 100) / total).toInt()
        val last = loggedPercent[e.dmId] ?: 0
        if (pct - last >= 10 || (pct >= 100 && last < 100)) {
            loggedPercent[e.dmId] = pct
            AppLog.i(
                AppLog.CAT_DL,
                "progress ${pct}%: \"${e.fileName}\" (${fmtMB(soFar)} / ${fmtMB(total)})",
            )
        }
    }

    /** Moves a file that finished downloading while the app was closed (or a
     * failed earlier move) from the private staging folder into public
     * Downloads/MovieBox, updating the history entry. */
    private suspend fun importStagedFile(e: HistoryEntry) {
        val staged = e.localUri ?: return
        AppLog.i(AppLog.CAT_DL, "found \"${e.fileName}\" still waiting to be moved into public Downloads — doing that now…")
        val finalUri = repo.importToMediaStore(staged, e.fileName, e.mime)
        if (finalUri != null) {
            history.updateWhere(e.dmId) { it.copy(localUri = finalUri, needsImport = false) }
        } else {
            history.updateWhere(e.dmId) { it.copy(needsImport = false) }
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
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "CDN returned an unexpected HTTP code (link may have expired)"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "transfer failed mid-way (HTTP data error)"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "too many redirects"
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "insufficient storage space on the device"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "external storage/sdcard not found"
        DownloadManager.ERROR_CANNOT_RESUME -> "cannot resume the partial download"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "file already exists"
        else -> "reason code $r"
    }

    private fun dmPauseText(r: Int): String = when (r) {
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "waiting for network — the download resumes automatically"
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "waiting to retry"
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "queued for Wi-Fi"
        else -> "paused (code $r)"
    }

    fun startPolling() {
        if (pollJob?.isActive == true) return
        AppLog.i(AppLog.CAT_LIFE, "download status poller started (checks the system DownloadManager every second while downloads are active)")
        pollJob = viewModelScope.launch {
            while (true) {
                val active = history.entries.value.count {
                    it.status == "queued" || it.status == "running"
                }
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

    /* ---------------------------------------------------------------- */
    /* Debug / end-to-end diagnostics                                    */
    /* ---------------------------------------------------------------- */

    data class DebugStep(
        val title: String,
        val status: String = "pending",   // pending | running | ok | fail
        val summary: String = "",
        val raw: String = "",             // raw request/response captures
        val ms: Long = 0,
    )

    data class DebugUiState(
        val running: Boolean = false,
        val steps: List<DebugStep> = emptyList(),
        val report: String? = null,
    )

    private val _debugUi = MutableStateFlow(DebugUiState())
    val debugUi: StateFlow<DebugUiState> = _debugUi.asStateFlow()

    /** Runs the exact pipeline the app uses, live, on this device:
     *  connectivity -> guest token -> search -> detail -> play streams ->
     *  captions -> CDN download probe. Each step captures the raw HTTP
     *  exchange so failures are self-explanatory. */
    fun runDiagnostics() {
        if (_debugUi.value.running) return
        viewModelScope.launch {
            val titles = listOf(
                "Network connectivity",
                "Guest token (country-code)",
                "Search API (\"friends\")",
                "Detail API",
                "Play API (streams / quality)",
                "Caption API (subtitles)",
                "CDN download probe (Range 1KB)",
                "NSFW poster classifier (SafeSearch wall 2)",
            )
            _debugUi.value = DebugUiState(running = true, steps = titles.map { DebugStep(it) })

            // Step 0 — connectivity (no HTTP, read from the OS)
            runStep(0, titles[0]) {
                val info = buildDeviceInfo()
                if (info.contains("no active network")) {
                    throw IllegalStateException("Device has no active network — enable Wi-Fi or data and retry")
                }
                info to Unit
            }

            // Step 1 — guest token, forced live fetch
            runStep(1, titles[1]) {
                val t = MovieBoxApi.guestToken(forceRefresh = true)
                "token acquired (${t.length} chars, starts \"${t.take(18)}…\")" to t
            }

            // Step 2 — search
            val first: SearchResult? = runStep(2, titles[2]) {
                val r = MovieBoxApi.search("friends")
                if (r.isEmpty()) throw IllegalStateException("search returned 0 results")
                "OK — ${r.size} results; first: \"${r[0].title}\" (${r[0].detailPath})" to r[0]
            }

            // Step 3 — detail
            var detail: DetailData? = null
            runStep(3, titles[3]) {
                val slug = first?.detailPath ?: "friends-QqM8Vz4IoH"
                val d = MovieBoxApi.fetchDetail(slug)
                detail = d
                val seasons = (d.content as? ContentLayout.Episodes)?.seasons?.size ?: 0
                "OK — \"${d.title}\" [${d.type}] id=${d.subjectId}, seasons=$seasons, dubs=${d.dubs.size}" to d
            }

            // Step 4 — play streams
            var links: PlayResult? = null
            runStep(4, titles[4]) {
                val d = detail ?: throw IllegalStateException("no detail from previous step")
                val se = (d.content as? ContentLayout.Episodes)?.seasons?.firstOrNull()?.se ?: 0
                val p = MovieBoxApi.fetchLinks(d.detailPath, d.subjectId, se, 1)
                links = p
                val s = p.streams.joinToString { "${it.resolutionInt}p (${it.size.ifEmpty { "?" }})" }
                val summary = if (p.streams.isEmpty())
                    "play API OK but 0 downloadable streams (title may be VIP-only)"
                else "OK — ${p.streams.size} streams: $s"
                summary to p
            }

            // Step 5 — captions
            runStep(5, titles[5]) {
                val d = detail ?: throw IllegalStateException("no detail from previous step")
                val p = links ?: throw IllegalStateException("no streams from previous step")
                val sid = p.streams.firstOrNull()?.id
                    ?: throw IllegalStateException("no stream id to query captions")
                val caps: List<CaptionItem> = MovieBoxApi.fetchCaptions(d.detailPath, d.subjectId, sid)
                if (caps.isEmpty()) "caption API OK but 0 subtitles for this stream" to caps
                else "OK — ${caps.size} languages: ${caps.joinToString { it.lanName }}" to caps
            }

            // Step 6 — CDN probe
            runStep(6, titles[6]) {
                val p = links ?: throw IllegalStateException("no streams from previous step")
                val stream = p.streams.lastOrNull()
                    ?: throw IllegalStateException("no stream URL to probe")
                MovieBoxApi.probeCdn(stream.url) to Unit
            }

            // Step 7 — NSFW poster classifier (wall 2)
            runStep(7, titles[7]) {
                val app = getApplication<Application>()
                if (!NsfwImageClassifier.ensureLoaded(app)) {
                    throw IllegalStateException("model failed to load — wall 2 disabled (text wall still active)")
                }
                val cover = detail?.cover ?: first?.cover ?: ""
                if (cover.isEmpty()) throw IllegalStateException("no cover URL from previous steps")
                val t0 = System.currentTimeMillis()
                val v = NsfwImageClassifier.classifyCoverBlocking(app, cover, detail?.title ?: "")
                val ms = System.currentTimeMillis() - t0
                val s = v.scores ?: throw IllegalStateException("classification returned no scores (image download/decode failed?)")
                "OK — ${s.top()} — verdict ${if (v.isNsfw) "BLOCKED (${v.reason})" else "pass"} (${ms} ms)" to v
            }

            // Final report build must never crash the diagnostics coroutine
            // (e.g. SecurityException reading network state on odd OEM ROMs).
            val finalReport = try {
                buildReport()
            } catch (e: Exception) {
                "Report build failed: ${e.javaClass.simpleName}: ${e.message ?: ""}\n" +
                    "(steps above are still valid — copy them individually)"
            }
            _debugUi.value = _debugUi.value.copy(running = false, report = finalReport)
        }
    }

    private suspend fun <T> runStep(
        index: Int,
        title: String,
        block: suspend () -> Pair<String, T>,
    ): T? {
        updateStep(index) { it.copy(status = "running") }
        val t0 = System.currentTimeMillis()
        ApiDebug.beginStep(title)
        return try {
            val (summary, value) = block()
            val raw = ApiDebug.endStep()
            updateStep(index) {
                it.copy(status = "ok", summary = summary, raw = rawToText(raw), ms = System.currentTimeMillis() - t0)
            }
            AppLog.ok(AppLog.CAT_LIFE, "E2E test [${index + 1}/8] ${title} — OK (${System.currentTimeMillis() - t0} ms): $summary")
            value
        } catch (e: Exception) {
            val raw = ApiDebug.endStep()
            updateStep(index) {
                it.copy(
                    status = "fail",
                    summary = "${e.javaClass.simpleName}: ${e.message ?: ""}",
                    raw = rawToText(raw),
                    ms = System.currentTimeMillis() - t0,
                )
            }
            AppLog.e(AppLog.CAT_LIFE, "E2E test [${index + 1}/8] ${title} — FAILED: ${e.javaClass.simpleName}: ${e.message ?: ""}")
            null
        }
    }

    private fun updateStep(index: Int, transform: (DebugStep) -> DebugStep) {
        _debugUi.value = _debugUi.value.copy(
            steps = _debugUi.value.steps.mapIndexed { i, s -> if (i == index) transform(s) else s }
        )
    }

    private fun rawToText(raw: List<ApiDebug.Exchange>): String = raw.joinToString("\n\n") { ex ->
        buildString {
            append("${ex.method} ${ex.url}\n")
            append("  ${ex.httpCode ?: "no response"} · ${ex.ms} ms · ${if (ex.ok) "OK" else "FAILED"}\n")
            append("  ${ex.snippet.take(1500)}")
        }
    }

    fun buildReport(): String {
        val sb = StringBuilder()
        sb.append("MovieBox Downloader — end-to-end diagnostic report\n")
        sb.append("Time: ")
            .append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
            .append('\n')
        sb.append(buildDeviceInfo()).append('\n')
        sb.append("\n== STEPS ==\n")
        for ((i, s) in _debugUi.value.steps.withIndex()) {
            sb.append("\n[Step ${i + 1}] ${s.title} — ${s.status.uppercase()} (${s.ms} ms)\n")
            if (s.summary.isNotEmpty()) sb.append("  ${s.summary}\n")
            if (s.raw.isNotEmpty()) sb.append(s.raw).append('\n')
        }
        sb.append("\n== LAST APP TRAFFIC (live log) ==\n")
        for (ex in ApiDebug.exchanges.value.takeLast(30)) {
            sb.append("${ex.step} | ${ex.method} ${ex.url} -> ${ex.httpCode ?: "no-response"} (${ex.ms} ms)")
            if (!ex.ok) sb.append(" FAILED: ").append(ex.snippet.take(200))
            sb.append('\n')
        }
        sb.append("\n== RECENT APP ACTIVITY (unified log) ==\n")
        for (ev in AppLog.events.value.takeLast(40)) {
            sb.append("[${ev.cat}/${ev.level}] ${ev.msg}\n")
        }
        return sb.toString()
    }

    fun clearDebugLog() = ApiDebug.clear()

    /* ---- live activity log (AppLog) surface for the debug screen ---- */

    fun deviceInfo(): String = buildDeviceInfo()

    /** Full session export: device info + every recorded event with details. */
    fun fullLogText(): String =
        AppLog.export(header = "MovieBox Downloader — full session activity log\n${buildDeviceInfo()}")

    /** Android share sheet (WhatsApp / Telegram / email …). */
    fun shareFullLog(context: android.content.Context) {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, fullLogText())
            }
            context.startActivity(android.content.Intent.createChooser(intent, "Share debug log"))
            AppLog.i(AppLog.CAT_UI, "debug log shared via the Android share sheet")
        } catch (e: Exception) {
            AppLog.e(AppLog.CAT_UI, "share failed: ${e.javaClass.simpleName}: ${e.message ?: ""}")
        }
    }

    fun previousSessionText(): String = AppLog.exportPrevious()

    fun clearAppLog() = AppLog.clear()

    private fun buildDeviceInfo(): String {
        val app = getApplication<Application>()
        val version = try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        // Defensive: on some OEM builds / reinstalls these calls can throw
        // SecurityException even with the permission declared. A diagnostic
        // report must never crash the app.
        val netInfo = try {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            val net = cm?.activeNetwork
            val caps = net?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
            val desc = when {
                net == null -> "no active network"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
                else -> "other"
            }
            val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            "$desc, internet=$hasInternet${if (validated) ", validated" else ", NOT validated"}"
        } catch (e: Exception) {
            "unavailable (${e.javaClass.simpleName}: ${e.message ?: ""})"
        }
        val netPermGranted = try {
            app.checkSelfPermission(android.Manifest.permission.ACCESS_NETWORK_STATE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
        return "App: MovieBox Downloader v$version\n" +
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "Network: $netInfo\n" +
            "Permissions: INTERNET=granted, ACCESS_NETWORK_STATE=${if (netPermGranted) "granted" else "MISSING"}"
    }

    /* ---------------------------------------------------------------- */
    /* Helpers                                                           */
    /* ---------------------------------------------------------------- */

    private fun pickStream(streams: List<StreamItem>, wanted: Int): StreamItem? {
        if (streams.isEmpty()) return null
        streams.firstOrNull { it.resolutionInt == wanted }?.let { return it }
        // closest at or below the wanted quality, else the lowest available
        streams.lastOrNull { it.resolutionInt <= wanted }?.let { return it }
        return streams.first()
    }

    private fun buildFileName(d: DetailData, se: Int, ep: Int, resolution: Int): String {
        val base = when (d.content) {
            is ContentLayout.Episodes -> "${d.title} ${seasonEp(se, ep)}"
            is ContentLayout.Parts -> {
                val parts = (d.content as ContentLayout.Parts).items
                if (parts.size > 1 && se != 0) "${d.title} Part $se"
                else d.title
            }
        }
        return sanitize("${base} ${resolution}p.mp4")
    }

    private fun episodeLabel(d: DetailData, se: Int, ep: Int): String = when (d.content) {
        is ContentLayout.Episodes -> seasonEp(se, ep)
        is ContentLayout.Parts -> {
            val parts = (d.content as ContentLayout.Parts).items
            if (parts.size > 1 && se != 0) "Part $se" else "Movie"
        }
    }

    private fun seasonEp(se: Int, ep: Int): String =
        "S${String.format("%02d", se)} E${String.format("%02d", ep)}"

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[^\\w.\\- ()\\[\\]]+"), "_").trim()
        return cleaned.ifEmpty { "download.mp4" }
    }

    private fun friendlyError(e: Exception): String {
        val name = e.javaClass.simpleName
        val msg = (e.message ?: "").trim()
        return when {
            msg.contains("timeout", true) || name.contains("Timeout") ->
                "Network timeout — check your connection and retry"
            name == "UnknownHostException" ->
                "No internet / DNS failure ($msg)"
            name == "NetworkOnMainThreadException" ->
                "Internal bug: network on main thread — please report"
            name.startsWith("SSL") || name.contains("Handshake") ->
                "SSL/TLS error: $msg"
            msg.isEmpty() -> name
            else -> "$name: $msg"
        }
    }
    fun buildReport(): String = debugLogic.buildReport()
    fun clearDebugLog() = debugLogic.clearDebugLog()
    fun deviceInfo(): String = debugLogic.deviceInfo()
    fun fullLogText(): String = debugLogic.fullLogText()
    fun shareFullLog(context: Context) = debugLogic.shareFullLog(context)
    fun previousSessionText(): String = debugLogic.previousSessionText()
    fun clearAppLog() = debugLogic.clearAppLog()
}
