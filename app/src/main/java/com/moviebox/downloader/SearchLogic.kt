package com.moviebox.downloader

import android.app.Application
import com.moviebox.downloader.api.CaptionItem
import com.moviebox.downloader.api.ContentLayout
import com.moviebox.downloader.api.DetailData
import com.moviebox.downloader.api.MovieBoxApi
import com.moviebox.downloader.api.PlayResult
import com.moviebox.downloader.api.SearchResult
import com.moviebox.downloader.api.StreamItem
import com.moviebox.downloader.debug.AppLog
import com.moviebox.downloader.util.ContentAnalyzer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

class SearchLogic(private val app: Application) {

    var safeSearch: Boolean = true
        set(value) {
            field = value
            if (field) {
                com.moviebox.downloader.util.NsfwDetector.clearCache()
                verifiedAdult.clear()
            }
        }

    var query: String = ""
        set(value) { field = value }

    var lastRawResults: List<SearchResult> = emptyList()
    private val _recentQueries = MutableStateFlow<List<String>>(emptyList())
    val recentQueries: StateFlow<List<String>> = _recentQueries.asStateFlow()
    val verifiedAdult: MutableMap<String, Boolean> = mutableMapOf()

    fun applyFilter(raw: List<SearchResult>): Pair<List<SearchResult>, List<SearchResult>> {
        if (!safeSearch) return raw to emptyList()
        return raw.partition {
            !ContentAnalyzer.isAdultText(it.title, it.genres) &&
                verifiedAdult[it.detailPath] != true
        }
    }

    fun removeRecentQuery(q: String) {
        val cur = _recentQueries.value.toMutableList()
        cur.remove(q)
        _recentQueries.value = cur
    }

    suspend fun search(
        query: String,
        onOpenDetail: (String) -> Unit,
        onError: (String) -> Unit,
        onResult: (List<SearchResult>, Int) -> Unit,
        onSaveQuery: (String) -> Unit,
    ) {
        val input = query.trim()
        if (input.isEmpty()) return

        val slug = MovieBoxApi.resolveSlug(input)
        if (slug != null && (input.startsWith("http", true) || input.contains('/'))) {
            onOpenDetail(slug)
            return
        }

        if (safeSearch && ContentAnalyzer.isAdultQuery(input)) {
            onError("Blocked by SafeSearch — this search looks like adult content. Turn SafeSearch off to search for it.")
            return
        }

        try {
            val raw = MovieBoxApi.search(input)
            lastRawResults = raw
            val (cheapKept, cheapBlocked) = applyFilter(raw)
            val results = if (safeSearch) verifySafe(cheapKept) else cheapKept
            val blockedCount = raw.size - results.size
            if (blockedCount > 0) {
                AppLog.i(
                    AppLog.CAT_UI,
                    "SafeSearch hid $blockedCount adult result(s) (cheap: ${cheapBlocked.size}, detail-verified: ${blockedCount - cheapBlocked.size})"
                )
            }
            AppLog.ok(AppLog.CAT_NET, "search complete: ${results.size} shown / ${raw.size} total for \"$input\"")
            if (results.isNotEmpty()) {
                val cur = _recentQueries.value.toMutableList()
                cur.remove(input)
                cur.add(0, input)
                if (cur.size > 20) cur.removeAt(cur.lastIndex)
                _recentQueries.value = cur
                onSaveQuery(input)
            }
            onResult(results, blockedCount)
        } catch (e: Exception) {
            AppLog.e(AppLog.CAT_NET, "search FAILED: ${friendlyError(e)}")
            onError(friendlyError(e))
        }
    }

    suspend fun verifySafe(survivors: List<SearchResult>): List<SearchResult> {
        if (survivors.isEmpty()) return survivors
        val detailSem = Semaphore(3)
        return coroutineScope {
            survivors.mapIndexed { index: Int, item: SearchResult ->
                async {
                    if (index > 0) delay(index * 120L)
                    detailSem.withPermit {
                        val cached = verifiedAdult[item.detailPath]
                        if (cached != null) return@withPermit if (cached) null else item
                        val verdict = try {
                            withTimeoutOrNull(10_000) {
                                val d = MovieBoxApi.fetchDetail(item.detailPath)
                                val analysis = ContentAnalyzer.analyze(
                                    app, d.title, d.description, d.genres,
                                    if (safeSearch) d.cover else null,
                                )
                                if (ContentAnalyzer.isAdult(analysis)) {
                                    verifiedAdult[item.detailPath] = true
                                    AppLog.i(
                                        AppLog.CAT_UI,
                                        "SafeSearch hid \"${d.title}\" (${analysis.reasons.joinToString()})",
                                    )
                                    true
                                } else false
                            }
                        } catch (e: Exception) {
                            AppLog.warn(AppLog.CAT_NET, "SafeSearch verify failed for \"${item.title}\"", e.message ?: "")
                            null
                        }
                        when (verdict) {
                            null -> item
                            true -> null
                            false -> item
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    suspend fun loadDetail(
        slug: String,
        safeSearch: Boolean,
        onBlocked: (String) -> Unit,
        onLoaded: (DetailData) -> Unit,
    ) {
        try {
            val d = MovieBoxApi.fetchDetail(slug)
            if (safeSearch && ContentAnalyzer.isAdultDetail(d.title, d.description, d.genres)) {
                onBlocked("Blocked by SafeSearch — this title looks like adult content.\n\n" +
                    "Turn SafeSearch off on the search screen if you want to open it anyway.")
                return
            }
            if (safeSearch) {
                try {
                    val analysis = withTimeoutOrNull(8_000) {
                        ContentAnalyzer.analyze(app, d.title, d.description, d.genres, d.cover)
                    }
                    if (analysis != null && ContentAnalyzer.isAdult(analysis)) {
                        onBlocked("Blocked by SafeSearch — this title appears to contain adult content.\n\n" +
                            "Turn SafeSearch off on the search screen if you want to open it anyway.")
                        return
                    }
                } catch (e: Exception) {
                    AppLog.warn(AppLog.CAT_NET, "SafeSearch analysis failed for detail \"${d.title}\": ${e.message}")
                }
            }
            onLoaded(d)
        } catch (e: Exception) {
            AppLog.e(AppLog.CAT_NET, "detail load FAILED for \"$slug\": ${friendlyError(e)}")
            onBlocked(friendlyError(e))
        }
    }

    suspend fun refreshLinks(
        detail: DetailData,
        selectedSe: Int,
        selectedResolution: Int,
        selectedSubtitles: Set<String>,
        onUpdated: (PlayResult, Int, Set<String>) -> Unit,
        onError: (String) -> Unit,
    ) {
        val se = selectedSe
        val ep = if (detail.content is ContentLayout.Episodes) 1 else 0
        AppLog.i(AppLog.CAT_NET, "loading play streams + subtitles for \"${detail.title}\"…")
        try {
            val links: PlayResult = MovieBoxApi.fetchLinks(detail.detailPath, detail.subjectId, se, ep)
            val available = links.streams.map { it.resolutionInt }
            val defaultRes = available.lastOrNull() ?: 0
            val keepRes = selectedResolution
            val capNames = links.captions.map { it.lanName }.toSet()
            onUpdated(links, if (keepRes > 0 && keepRes in available) keepRes else defaultRes, selectedSubtitles.filter { it in capNames }.toSet())
            AppLog.ok(AppLog.CAT_NET, "QUALITY + SUBTITLES chips updated: ${links.streams.size} qualities, ${links.captions.size} subtitle languages")
        } catch (e: Exception) {
            AppLog.e(AppLog.CAT_NET, "streams/subtitles FAILED: ${friendlyError(e)}")
            onError(friendlyError(e))
        }
    }

    fun friendlyError(e: Exception): String = when {
        e.message?.contains("401") == true -> "Session expired — please retry"
        e.message?.contains("empty response") == true -> "No results — the site may have changed"
        else -> e.message ?: "Unknown error"
    }

    fun pickStream(streams: List<StreamItem>, wanted: Int): StreamItem? {
        if (streams.isEmpty()) return null
        streams.firstOrNull { it.resolutionInt == wanted }?.let { return it }
        streams.lastOrNull { it.resolutionInt <= wanted }?.let { return it }
        return streams.first()
    }

    fun buildFileName(d: DetailData, se: Int, ep: Int, resolution: Int): String {
        val base = when (d.content) {
            is ContentLayout.Episodes -> "${d.title} ${seasonEp(se, ep)}"
            is ContentLayout.Parts -> "${d.title} Part ${se}"
        }
        return "$base [$resolution]p.mp4"
    }

    private fun episodeLabel(d: DetailData, se: Int, ep: Int): String = when (d.content) {
        is ContentLayout.Episodes -> "S${String.format("%02d", se)} E${String.format("%02d", ep)}"
        is ContentLayout.Parts -> "Part $se"
    }

    private fun seasonEp(se: Int, ep: Int): String =
        "S${String.format("%02d", se)} E${String.format("%02d", ep)}"
}
