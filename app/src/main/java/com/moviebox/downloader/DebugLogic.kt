package com.moviebox.downloader

import android.app.Application
import android.content.Context
import com.moviebox.downloader.api.ApiDebug
import com.moviebox.downloader.api.CaptionItem
import com.moviebox.downloader.api.ContentLayout
import com.moviebox.downloader.api.DetailData
import com.moviebox.downloader.api.MovieBoxApi
import com.moviebox.downloader.api.PlayResult
import com.moviebox.downloader.api.SearchResult
import com.moviebox.downloader.debug.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DebugLogic(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _debugUi = MutableStateFlow(DebugUiState())
    val debugUi: StateFlow<DebugUiState> = _debugUi.asStateFlow()

    fun clearDebugLog() = ApiDebug.clear()

    fun deviceInfo(): String = buildDeviceInfo()

    fun fullLogText(): String =
        AppLog.export(header = "MovieBox Downloader — full session activity log\n${buildDeviceInfo()}")

    fun shareFullLog(context: Context) {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, fullLogText())
            }
            context.startActivity(android.content.Intent.createChooser(intent, "Share debug log"))
            AppLog.i(AppLog.CAT_UI, "debug log shared")
        } catch (e: Exception) {
            AppLog.e(AppLog.CAT_UI, "share failed: ${e.javaClass.simpleName}: ${e.message ?: ""}")
        }
    }

    fun previousSessionText(): String = AppLog.exportPrevious()
    fun clearAppLog() = AppLog.clear()

    fun runDiagnostics(onReport: (String) -> Unit) {
        if (_debugUi.value.running) return
        AppLog.i(AppLog.CAT_LIFE, "E2E diagnostics started")
        val titles = listOf(
            "Network connectivity",
            "Guest token (country-code)",
            "Search API (friends)",
            "Detail API",
            "Play API (streams / quality)",
            "Caption API (subtitles)",
            "CDN download probe (Range 1KB)",
        )
        _debugUi.value = DebugUiState(running = true, steps = titles.map { DebugStep(it) })

        var first: SearchResult? = null
        var detail: DetailData? = null
        var links: PlayResult? = null

        scope.launch {
            try {
                // Step 0
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val info = buildDeviceInfo()
                    if (info.contains("no active network")) throw IllegalStateException("no active network")
                }
                markStep(0, "ok", "OK — device has network")

                // Step 1
                try {
                    val t = MovieBoxApi.guestToken(forceRefresh = true)
                    markStep(1, "ok", "token acquired (${t.length} chars)")
                } catch (e: Exception) {
                    markStep(1, "fail", "${e.javaClass.simpleName}: ${e.message}")
                }

                // Step 2
                try {
                    val r = MovieBoxApi.search("friends")
                    if (r.isEmpty()) throw IllegalStateException("search returned 0 results")
                    first = r[0]
                    markStep(2, "ok", "OK — ${r.size} results; first: \"${r[0].title}\"")
                } catch (e: Exception) {
                    markStep(2, "fail", "${e.javaClass.simpleName}: ${e.message}")
                }

                // Step 3
                try {
                    val slug = first?.detailPath ?: "friends-QqM8Vz4IoH"
                    val d = MovieBoxApi.fetchDetail(slug)
                    detail = d
                    val seasons = (d.content as? ContentLayout.Episodes)?.seasons?.size ?: 0
                    markStep(3, "ok", "OK — \"${d.title}\" [${d.type}] id=${d.subjectId}, seasons=$seasons")
                } catch (e: Exception) {
                    markStep(3, "fail", "${e.javaClass.simpleName}: ${e.message}")
                }

                // Step 4
                try {
                    val d = detail ?: throw IllegalStateException("no detail")
                    val se = (d.content as? ContentLayout.Episodes)?.seasons?.firstOrNull()?.se ?: 0
                    val p = MovieBoxApi.fetchLinks(d.detailPath, d.subjectId, se, 1)
                    links = p
                    val s = p.streams.joinToString { "${it.resolutionInt}p (${it.size.ifEmpty { "?" }})" }
                    val summary = if (p.streams.isEmpty()) "play API OK but 0 streams" else "OK — ${p.streams.size} streams: $s"
                    markStep(4, "ok", summary)
                } catch (e: Exception) {
                    markStep(4, "fail", "${e.javaClass.simpleName}: ${e.message}")
                }

                // Step 5
                try {
                    val d = detail ?: throw IllegalStateException("no detail")
                    val p = links ?: throw IllegalStateException("no streams")
                    val sid = p.streams.firstOrNull()?.id ?: throw IllegalStateException("no stream id")
                    val caps: List<CaptionItem> = MovieBoxApi.fetchCaptions(d.detailPath, d.subjectId, sid)
                    if (caps.isEmpty()) markStep(5, "ok", "caption API OK but 0 subtitles") else markStep(5, "ok", "OK — ${caps.size} languages: ${caps.joinToString { it.lanName }}")
                } catch (e: Exception) {
                    markStep(5, "fail", "${e.javaClass.simpleName}: ${e.message}")
                }

                // Step 6
                try {
                    val p = links ?: throw IllegalStateException("no streams")
                    val stream = p.streams.lastOrNull() ?: throw IllegalStateException("no stream URL")
                    MovieBoxApi.probeCdn(stream.url)
                    markStep(6, "ok", "CDN probe completed")
                } catch (e: Exception) {
                    markStep(6, "fail", "${e.javaClass.simpleName}: ${e.message}")
                }

                val report = buildReport()
                _debugUi.value = _debugUi.value.copy(running = false, report = report)
                onReport(report)
                AppLog.ok(AppLog.CAT_LIFE, "E2E diagnostics completed")
            } catch (e: Exception) {
                _debugUi.value = _debugUi.value.copy(running = false)
                AppLog.e(AppLog.CAT_LIFE, "E2E diagnostics FAILED: ${e.message}")
            }
        }
    }

    private fun markStep(index: Int, status: String, summary: String) {
        _debugUi.value = _debugUi.value.copy(
            steps = _debugUi.value.steps.mapIndexed { i, s ->
                if (i == index) s.copy(status = status, summary = summary) else s
            }
        )
    }

    fun buildReport(): String {
        val sb = StringBuilder()
        sb.append("MovieBox Downloader — end-to-end diagnostic report\n")
        sb.append("Time: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        sb.append(buildDeviceInfo()).append('\n')
        sb.append("\n== STEPS ==\n")
        for ((i, s) in _debugUi.value.steps.withIndex()) {
            sb.append("\n[Step ${i + 1}] ${s.title} — ${s.status.uppercase()} (${s.ms} ms)\n")
            if (s.summary.isNotEmpty()) sb.append("  ${s.summary}\n")
        }
        sb.append("\n== LAST APP TRAFFIC ==\n")
        for (ex in ApiDebug.exchanges.value.takeLast(30)) {
            sb.append("${ex.step} | ${ex.method} ${ex.url} -> ${ex.httpCode ?: "no-response"} (${ex.ms} ms)\n")
        }
        sb.append("\n== RECENT APP ACTIVITY ==\n")
        for (ev in AppLog.events.value.takeLast(40)) {
            sb.append("[${ev.cat}/${ev.level}] ${ev.msg}\n")
        }
        return sb.toString()
    }

    private fun buildDeviceInfo(): String {
        val app = app
        val version = try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        val netInfo = try {
            val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
            val net = cm?.activeNetwork
            val caps = net?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
            val desc = when {
                net == null -> "no active network"
                caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
                caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
                else -> "other"
            }
            val validated = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            "$desc, internet=${caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true}${if (validated) ", validated" else ", NOT validated"}"
        } catch (e: Exception) {
            "unavailable (${e.javaClass.simpleName}: ${e.message ?: ""})"
        }
        return "App: MovieBox Downloader v$version\n" +
            "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n" +
            "Network: $netInfo"
    }

    fun cleanup() {
        scope.cancel()
    }
}
