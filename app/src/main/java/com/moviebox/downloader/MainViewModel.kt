package com.moviebox.downloader

import android.app.Application
import android.app.DownloadManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moviebox.downloader.api.ApiDebug
import com.moviebox.downloader.api.CaptionItem
import com.moviebox.downloader.api.ContentLayout
import com.moviebox.downloader.api.DetailData
import com.moviebox.downloader.api.MovieBoxApi
import com.moviebox.downloader.api.PlayResult
import com.moviebox.downloader.api.SearchResult
import com.moviebox.downloader.api.StreamItem
import com.moviebox.downloader.data.DownloadRepository
import com.moviebox.downloader.data.DmStatus
import com.moviebox.downloader.data.HistoryEntry
import com.moviebox.downloader.data.HistoryStore
import com.moviebox.downloader.debug.AppLog
import com.moviebox.downloader.util.ContentFilter
import android.content.Context
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

class MainViewModel(app: Application) : AndroidViewModel(app) {

    /* ---------------------------------------------------------------- */
    /* Navigation                                                        */
    /* ---------------------------------------------------------------- */

    sealed class Screen {
        object Home : Screen()
        data class Detail(val slug: String) : Screen()
        object Downloads : Screen()
        object Debug : Screen()
    }

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set
    private val backStack = ArrayDeque<Screen>()

    val canGoBack: Boolean get() = backStack.isNotEmpty()

    fun openDetail(slug: String) {
        if (screen is Screen.Detail && (screen as Screen.Detail).slug == slug) return
        AppLog.i(AppLog.CAT_NAV, "open detail page: $slug")
        backStack.addLast(screen)
        screen = Screen.Detail(slug)
        loadDetail(slug)
    }

    fun goBack(): Boolean {
        if (backStack.isEmpty()) return false
        screen = backStack.removeLast()
        AppLog.i(AppLog.CAT_NAV, "back navigation → ${screenName(screen)}")
        return true
    }

    fun navigateHome() {
        AppLog.i(AppLog.CAT_NAV, "navigate → Home (search screen)")
        backStack.clear()
        screen = Screen.Home
    }

    fun navigateDownloads() {
        AppLog.i(AppLog.CAT_NAV, "navigate → Downloads (history screen)")
        backStack.clear()
        screen = Screen.Downloads
    }

    fun openDebug() {
        if (screen is Screen.Debug) return
        AppLog.i(AppLog.CAT_NAV, "open Debug screen (live activity log)")
        backStack.addLast(screen)
        screen = Screen.Debug
    }

    private fun screenName(s: Screen): String = when (s) {
        is Screen.Home -> "Home"
        is Screen.Detail -> "Detail(${s.slug})"
        is Screen.Downloads -> "Downloads"
        is Screen.Debug -> "Debug"
    }

    /* ---------------------------------------------------------------- */
    /* Home / search                                                     */
    /* ---------------------------------------------------------------- */

    var query by mutableStateOf("")

    data class HomeUiState(
        val loading: Boolean = false,
        val results: List<SearchResult> = emptyList(),
        val error: String? = null,
        val searched: Boolean = false,
        val blockedCount: Int = 0,
    )

    /** SafeSearch: hides adult titles from results & detail pages. On by default. */
    private val prefs = app.getSharedPreferences("moviebox_settings", Context.MODE_PRIVATE)

    /* ---------- recent searches (persisted, shown as chips on Home) ---------- */

    private val _recentQueries = MutableStateFlow(loadRecentQueries())
    val recentQueries: StateFlow<List<String>> = _recentQueries.asStateFlow()

    private fun loadRecentQueries(): List<String> =
        prefs.getString("recent_queries", null)
            ?.split('\u0001')
            ?.filter { it.isNotBlank() }
            ?.take(8)
            ?: emptyList()

    private fun saveRecentQuery(q: String) {
        val next = (listOf(q) + _recentQueries.value.filter { !it.equals(q, ignoreCase = true) }).take(8)
        _recentQueries.value = next
        prefs.edit().putString("recent_queries", next.joinToString("\u0001")).apply()
    }

    fun removeRecentQuery(q: String) {
        val next = _recentQueries.value.filter { !it.equals(q, ignoreCase = true) }
        _recentQueries.value = next
        prefs.edit().putString("recent_queries", next.joinToString("\u0001")).apply()
    }

    fun clearRecentQueries() {
        _recentQueries.value = emptyList()
        prefs.edit().remove("recent_queries").apply()
    }

    var safeSearch by mutableStateOf(prefs.getBoolean("safe_search", true))
        private set

    /** Raw (unfiltered) results of the last search, so toggling re-filters instantly. */
    private var lastRawResults: List<SearchResult> = emptyList()

    /**
     * SafeSearch v3.2 detail-verification cache: detailPath -> adult verdict.
     * Search-level data (title + genre CSV) is too thin to catch clean-titled
     * adult items — their cover posters used to show in the grid and only got
     * blocked after tapping. We now verify every survivor against its detail
     * page BEFORE the grid renders, and remember the verdict.
     */
    private val verifiedAdult = mutableMapOf<String, Boolean>()

    fun toggleSafeSearch() {
        safeSearch = !safeSearch
        prefs.edit().putBoolean("safe_search", safeSearch).apply()
        AppLog.i(AppLog.CAT_UI, "SafeSearch toggled ${if (safeSearch) "ON" else "OFF"}")
        // re-apply the filter to the current results without a new network call
        val ui = _homeUi.value
        if (ui.searched && lastRawResults.isNotEmpty()) {
            val (kept, blocked) = applyFilter(lastRawResults)
            _homeUi.value = ui.copy(results = kept, blockedCount = blocked.size)
        }
    }

    private fun applyFilter(raw: List<SearchResult>): Pair<List<SearchResult>, List<SearchResult>> {
        if (!safeSearch) return raw to emptyList()
        return raw.partition {
            !ContentFilter.isAdultResult(it.title, it.genres) &&
                verifiedAdult[it.detailPath] != true
        }
    }

    /**
     * Fetch each survivor's detail page and run the full isAdultDetail check
     * (title + description + genres) so blocked posters never render.
     * Bounded concurrency (6), per-item timeout, fail-open on error: a network
     * problem must never hide legit content.
     */
    private suspend fun verifySafe(survivors: List<SearchResult>): List<SearchResult> {
        if (survivors.isEmpty()) return survivors
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
                            withTimeoutOrNull(10_000) {
                                val d = MovieBoxApi.fetchDetail(item.detailPath)
                                ContentFilter.isAdultDetail(d.title, d.description, d.genres)
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

        // a pasted MovieBox link? -> go straight to detail
        val slug = MovieBoxApi.resolveSlug(input)
        if (slug != null && (input.startsWith("http", true) || input.contains('/'))) {
            AppLog.i(AppLog.CAT_UI, "search input is a MovieBox link → resolving to detail \"$slug\"")
            openDetail(slug)
            return
        }

        // SafeSearch query gate: refuse obviously adult-intent searches outright
        if (safeSearch && ContentFilter.isAdultQuery(input)) {
            AppLog.i(AppLog.CAT_UI, "SafeSearch blocked adult-intent query \"$input\"")
            _homeUi.value = HomeUiState(
                error = "Blocked by SafeSearch — this search looks like adult content. Turn SafeSearch off to search for it."
            )
            return
        }

        AppLog.i(AppLog.CAT_UI, "searching for \"$input\"…")
        _homeUi.value = HomeUiState(loading = true)
        viewModelScope.launch {
            try {
                val raw = MovieBoxApi.search(input)
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

    /* ---------------------------------------------------------------- */
    /* Detail                                                            */
    /* ---------------------------------------------------------------- */

    data class DetailUiState(
        val loading: Boolean = false,
        val error: String? = null,
        val detail: DetailData? = null,
        val selectedSe: Int = 0,
        val selectedResolution: Int = 0,
        val selectedSubtitles: Set<String> = emptySet(),
        val selectedEps: Set<Pair<Int, Int>> = emptySet(),
        val streams: List<StreamItem> = emptyList(),
        val captions: List<com.moviebox.downloader.api.CaptionItem> = emptyList(),
        val linksLoading: Boolean = false,
        val linksError: String? = null,
    )

    private val _detailUi = MutableStateFlow(DetailUiState())
    val detailUi: StateFlow<DetailUiState> = _detailUi.asStateFlow()

    private var lastSlug: String? = null

    fun loadDetail(slug: String) {
        if (slug == lastSlug && _detailUi.value.detail != null) {
            AppLog.i(AppLog.CAT_NAV, "detail for \"$slug\" already loaded — showing cached copy")
            return
        }
        lastSlug = slug
        AppLog.i(AppLog.CAT_NAV, "loading detail \"$slug\" from API…")
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

    /** Re-runs after a dub switch (new subject) — replaces current detail. */
    fun switchDub(detailPath: String) {
        if (detailPath.isEmpty()) return
        val slug = detailPath.substringAfterLast('/')
        AppLog.i(AppLog.CAT_UI, "dub/language switched → reloading detail as \"$slug\"")
        lastSlug = null
        if (screen is Screen.Detail) {
            screen = Screen.Detail(slug)
        }
        loadDetail(slug)
    }

    /** Retry the play/caption request (after an error or a network hiccup). */
    fun retryLinks() = refreshLinks()

    fun selectSeason(se: Int) {
        val ui = _detailUi.value
        if (ui.selectedSe == se) return
        AppLog.i(AppLog.CAT_UI, "season chip tapped: S${String.format("%02d", se)} — reloading streams for the new season")
        _detailUi.value = ui.copy(selectedSe = se, selectedEps = emptySet())
        refreshLinks()
    }

    fun selectResolution(res: Int) {
        if (_detailUi.value.selectedResolution == res) return
        AppLog.i(AppLog.CAT_UI, "quality chip tapped: ${res}p")
        _detailUi.value = _detailUi.value.copy(selectedResolution = res)
    }

    fun toggleSubtitle(lan: String) {
        val cur = _detailUi.value.selectedSubtitles
        val next = if (lan in cur) cur - lan else cur + lan
        AppLog.i(
            AppLog.CAT_UI,
            "subtitle chip tapped: $lan — ${next.size} subtitle(s) will be saved next to the video",
        )
        _detailUi.value = _detailUi.value.copy(selectedSubtitles = next)
    }

    fun toggleEp(se: Int, ep: Int) {
        val key = se to ep
        val cur = _detailUi.value.selectedEps
        val next = if (key in cur) cur - key else cur + key
        AppLog.i(
            AppLog.CAT_UI,
            "episode ${seasonEp(se, ep)} ${if (key in cur) "de" else ""}selected for batch download (${next.size} total)",
        )
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
        }
        AppLog.i(AppLog.CAT_UI, "select-all episodes tapped: ${all.size} selected")
        _detailUi.value = _detailUi.value.copy(selectedEps = all)
    }

    fun clearSelection() {
        AppLog.i(AppLog.CAT_UI, "batch selection cleared")
        _detailUi.value = _detailUi.value.copy(selectedEps = emptySet())
    }

    /** Fetch streams + captions for the current (detailPath, season) to build chips. */
    private fun refreshLinks() {
        val ui = _detailUi.value
        val d = ui.detail ?: return
        val se = ui.selectedSe
        val ep = if (d.content is ContentLayout.Episodes) 1 else 0
        AppLog.i(AppLog.CAT_NET, "loading play streams + subtitles for \"${d.title}\" ${if (ep == 1) seasonEp(se, ep) else ""}…")
        viewModelScope.launch {
            _detailUi.value = _detailUi.value.copy(linksLoading = true, linksError = null)
            try {
                val links: PlayResult = MovieBoxApi.fetchLinks(d.detailPath, d.subjectId, se, ep)
                val available = links.streams.map { it.resolutionInt }
                val defaultRes = available.lastOrNull() ?: 0
                val keepRes = _detailUi.value.selectedResolution
                val capNames = links.captions.map { it.lanName }.toSet()
                _detailUi.value = _detailUi.value.copy(
                    streams = links.streams,
                    captions = links.captions,
                    linksLoading = false,
                    // keep the user's pick only if it still exists in this season
                    selectedResolution = if (keepRes > 0 && keepRes in available) keepRes else defaultRes,
                    selectedSubtitles = _detailUi.value.selectedSubtitles.filter { it in capNames }.toSet(),
                )
                AppLog.ok(
                    AppLog.CAT_NET,
                    "QUALITY + SUBTITLES chips updated: ${links.streams.size} qualit${if (links.streams.size == 1) "y" else "ies"} (${available.joinToString { "${it}p" }}), ${links.captions.size} subtitle languages",
                )
            } catch (e: Exception) {
                AppLog.e(AppLog.CAT_NET, "streams/subtitles FAILED: ${friendlyError(e)} — QUALITY and SUBTITLES sections will show a Retry button")
                _detailUi.value = _detailUi.value.copy(linksLoading = false, linksError = friendlyError(e))
            }
        }
    }

    /* ---------------------------------------------------------------- */
    /* Downloads                                                         */
    /* ---------------------------------------------------------------- */

    private val history = HistoryStore(app)
    private val repo = DownloadRepository(app)

    val entries: StateFlow<List<HistoryEntry>> = history.entries

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun onMessageShown() {
        _message.value = null
    }

    fun onPermissionDenied() {
        AppLog.warn(AppLog.CAT_UI, "storage permission DENIED — on Android 8/9 downloads cannot be saved without it (Android 10+ does not need it)")
        _message.value = "Storage permission is needed to save downloads"
    }

    init {
        AppLog.i(AppLog.CAT_LIFE, "MainViewModel created — app state initialized, loading download history…")
        viewModelScope.launch {
            history.load()
            refreshStatuses()
            startPolling()
        }
    }

    fun downloadSelected() {
        val ui = _detailUi.value
        val d = ui.detail ?: return
        val eps = ui.selectedEps.sortedWith(compareBy({ it.first }, { it.second }))
        if (eps.isEmpty()) return
        AppLog.i(
            AppLog.CAT_UI,
            "batch download button tapped: ${eps.size} episode(s) @ ${ui.selectedResolution}p, ${ui.selectedSubtitles.size} subtitle track(s)",
        )
        viewModelScope.launch {
            var ok = 0
            var failed = 0
            for ((se, ep) in eps) {
                val res = enqueueEpisode(d, se, ep)
                if (res) ok++ else failed++
                delay(700) // small delay so the CDN does not rate-limit
            }
            _detailUi.value = _detailUi.value.copy(selectedEps = emptySet())
            _message.value = when {
                ok > 0 && failed > 0 -> "$ok download(s) started, $failed failed"
                ok > 0 -> "Started $ok download${if (ok > 1) "s" else ""}"
                else -> "Download failed — try again"
            }
            AppLog.ok(AppLog.CAT_DL, "batch finished: $ok started, $failed failed")
            startPolling()
        }
    }

    /** Downloads a single episode/part without the batch bar. */
    fun downloadSingle(se: Int, ep: Int) {
        val d = _detailUi.value.detail ?: return
        AppLog.i(AppLog.CAT_UI, "Get button tapped: ${episodeLabel(d, se, ep)} @ ${_detailUi.value.selectedResolution}p")
        viewModelScope.launch {
            val ok = enqueueEpisode(d, se, ep)
            _message.value = if (ok) "Download started" else "Download failed — try again"
            startPolling()
        }
    }

    private suspend fun enqueueEpisode(d: DetailData, se: Int, ep: Int): Boolean {
        val ui = _detailUi.value
        AppLog.i(AppLog.CAT_DL, "resolving fresh signed link for ${episodeLabel(d, se, ep)} (links expire, so they are re-fetched per download)…")
        return try {
            val links = MovieBoxApi.fetchLinks(d.detailPath, d.subjectId, se, ep)
            val stream = pickStream(links.streams, ui.selectedResolution)
                ?: throw IllegalStateException("no stream available")
            val fileName = buildFileName(d, se, ep, stream.resolutionInt)
            val res = repo.enqueue(stream.url, fileName, "video/mp4")
            val dmId = res.dmId
            history.add(
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
            AppLog.ok(
                AppLog.CAT_DL,
                "\"$fileName\" is now downloading (${stream.resolutionInt}p, ${stream.size}, DownloadManager id #$dmId)",
            )
            // selected subtitles -> save alongside the video
            for (lan in ui.selectedSubtitles) {
                val cap = links.captions.firstOrNull { it.lanName == lan } ?: continue
                try {
                    val srtName = fileName.removeSuffix(".mp4") + " - ${cap.lanName}.srt"
                    val capRes = repo.enqueue(cap.url, srtName, "application/octet-stream")
                    history.add(
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
                    AppLog.ok(AppLog.CAT_DL, "subtitle file queued too: \"$srtName\" (id #${capRes.dmId})")
                } catch (_: Exception) {
                }
            }
            true
        } catch (e: Exception) {
            AppLog.e(
                AppLog.CAT_DL,
                "could NOT start download ${episodeLabel(d, se, ep)}: ${friendlyError(e)}",
                "the signed-link fetch or the DownloadManager enqueue failed — check the NET and DL entries above for the exact reason",
            )
            false
        }
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
            AppLog.ok(AppLog.CAT_LIFE, "E2E test [${index + 1}/7] ${title} — OK (${System.currentTimeMillis() - t0} ms): $summary")
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
            AppLog.e(AppLog.CAT_LIFE, "E2E test [${index + 1}/7] ${title} — FAILED: ${e.javaClass.simpleName}: ${e.message ?: ""}")
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
}
