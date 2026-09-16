package com.moviebox.downloader.api

import com.moviebox.downloader.debug.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Reverse-engineered API client for themoviebox.xyz (guest access, no login).
 * Kotlin port of the web app's src/lib/moviebox.ts.
 *
 * Every network call — including the guest-token bootstrap — runs on
 * Dispatchers.IO. (The guest-token call used to run on the caller's thread,
 * which crashed with NetworkOnMainThreadException on real devices and made
 * search / quality / subtitles fail.)
 */
object MovieBoxApi {

    const val API_BASE = "https://h5-api.aoneroom.com/wefeed-h5api-bff"
    const val PLAY_BASE = "https://themoviebox.xyz/wefeed-h5api-bff"
    const val SITE_ORIGIN = "https://themoviebox.xyz"
    const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Volatile
    private var cachedToken: String? = null
    private var tokenExpiry: Long = 0L
    private val tokenMutex = Mutex()

    /**
     * Guest token — issued by the site itself via Set-Cookie, valid ~90d.
     * The HTTP call always runs on Dispatchers.IO, no matter which thread
     * the caller is on. Pass [forceRefresh] to re-test the endpoint live
     * (used by the debug screen).
     */
    suspend fun guestToken(forceRefresh: Boolean = false): String = tokenMutex.withLock {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedToken != null && now < tokenExpiry - 3600_000L) {
            AppLog.i(AppLog.CAT_NET, "auth: using cached guest token (${cachedToken!!.length} chars, ${describeTokenAge(now)})")
            return@withLock cachedToken!!
        }
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            var httpCode: Int? = null
            try {
                val req = Request.Builder()
                    .url("$API_BASE/country-code")
                    .headers(baseHeaders())
                    .build()
                val newToken = client.newCall(req).execute().use { r ->
                    httpCode = r.code
                    if (!r.isSuccessful) throw IOException("country-code HTTP ${r.code}")
                    val cookies = r.headers.values("Set-Cookie")
                    cookies.firstNotNullOfOrNull { c ->
                        Regex("token=([^;]+)").find(c)?.groupValues?.get(1)
                    } ?: throw IOException("no guest token issued (Set-Cookie token missing)")
                }
                cachedToken = newToken
                // refresh daily-ish
                tokenExpiry = now + 6L * 24 * 3600 * 1000
                AppLog.ok(
                    AppLog.CAT_NET,
                    "auth: new guest token issued by server (${newToken.length} chars, ${System.currentTimeMillis() - t0} ms) — no login needed",
                    "GET $API_BASE/country-code → HTTP $httpCode\ntoken head: ${newToken.take(40)}… (${newToken.length} chars), cached ~6 days",
                )
                ApiDebug.record(
                    "GET", "$API_BASE/country-code", httpCode,
                    System.currentTimeMillis() - t0, true,
                    "guest token OK (${newToken.length} chars)",
                )
                newToken
            } catch (e: Exception) {
                AppLog.e(
                    AppLog.CAT_NET,
                    "auth: guest token request FAILED (${e.javaClass.simpleName}: ${e.message ?: ""})" +
                        if (cachedToken != null) " — falling back to previous token" else " — no token available, API calls requiring auth will fail",
                )
                ApiDebug.record(
                    "GET", "$API_BASE/country-code", httpCode,
                    System.currentTimeMillis() - t0, false,
                    "guest token FAILED — ${e.javaClass.simpleName}: ${e.message}",
                )
                // fall back to the old token if we have one
                cachedToken ?: throw IOException(
                    "guest token failed — ${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
    }

    private fun describeTokenAge(now: Long): String {
        val age = now - (tokenExpiry - 6L * 24 * 3600 * 1000)
        val h = age / 3600_000
        return if (h < 1) "just fetched" else "fetched ${h}h ago"
    }

    private fun baseHeaders(referer: String = "$SITE_ORIGIN/"): Headers = Headers.headersOf(
        "User-Agent", MOBILE_UA,
        "Origin", SITE_ORIGIN,
        "Referer", referer,
        "x-request-lang", "en",
    )

    private suspend fun authHeaders(referer: String = "$SITE_ORIGIN/"): Headers {
        val token = guestToken()
        return Headers.headersOf(
            "User-Agent", MOBILE_UA,
            "Origin", SITE_ORIGIN,
            "Referer", referer,
            "x-request-lang", "en",
            "Authorization", "Bearer $token",
        )
    }

    private suspend fun executeJson(
        url: String,
        headers: Headers,
        method: String = "GET",
        body: String? = null,
    ): JsonObject = withContext(Dispatchers.IO) {
        var attempt = 0
        var lastHttpCode: Int? = null
        var lastEx: Exception? = null
        while (attempt < MAX_ATTEMPTS) {
            if (attempt > 0) {
                val ms = backoff(attempt)
                AppLog.warn(
                    AppLog.CAT_NET,
                    "retrying ${method} ${pathOf(url)} (${attempt + 1}/${MAX_ATTEMPTS}) after ${ms}ms — ${lastEx?.javaClass?.simpleName}: ${lastEx?.message}",
                )
                delay(ms)
            }
            val t0 = System.currentTimeMillis()
            val builder = Request.Builder().url(url).headers(headers)
            when (method) {
                "POST" -> builder.post((body ?: "").toRequestBody("application/json".toMediaType()))
                else -> builder.get()
            }
            AppLog.i(
                AppLog.CAT_NET,
                "→ $method ${pathOf(url)}",
                requestDetail(url, headers, method, body),
            )

            var httpCode: Int? = null
            var responseBody = ""
            var rSuccessful = false
            var contentType: String? = null

            try {
                client.newCall(builder.build()).execute().use { r ->
                    httpCode = r.code
                    rSuccessful = r.isSuccessful
                    contentType = r.header("Content-Type")
                    responseBody = r.body?.string().orEmpty()
                }
            } catch (e: IOException) {
                lastEx = e
                if (httpCode == null) {
                    AppLog.e(
                        AppLog.CAT_NET,
                        "✗ $method ${hostOf(url)} FAILED — ${e.javaClass.simpleName}: ${e.message ?: ""}",
                        requestDetail(url, headers, method, body),
                    )
                    ApiDebug.record(
                        method, url, null, 0, false,
                        "${e.javaClass.simpleName}: ${e.message}",
                    )
                }
                if (attempt < MAX_ATTEMPTS - 1) {
                    attempt++
                    continue
                }
                throw e
            }

            val ms = System.currentTimeMillis() - t0
            AppLog.ok(
                AppLog.CAT_NET,
                "← HTTP ${httpCode} · ${"%.1f".format(responseBody.length / 1024.0)} KB · $ms ms · ${hostOf(url)}",
                responseDetail(url, httpCode ?: 0, contentType, responseBody),
            )
            ApiDebug.record(method, url, httpCode, ms, rSuccessful, responseBody)

            if (httpCode == 401 && attempt < MAX_ATTEMPTS - 1) {
                synchronized(tokenMutex) {
                    cachedToken = null
                    tokenExpiry = 0
                }
                AppLog.warn(AppLog.CAT_NET, "401 Unauthorized — invalidated guest token, retrying with fresh token")
                lastEx = IOException("401 Unauthorized")
                attempt++
                continue
            }
            lastHttpCode = httpCode

            if (!rSuccessful) throw IOException("HTTP $httpCode from ${hostOf(url)}")
            if (responseBody.isNullOrBlank()) throw IOException("empty response from ${hostOf(url)}")
            return@withContext parseObj(responseBody)
        }
        throw lastEx ?: IOException("HTTP ${lastHttpCode ?: "no response"} from ${hostOf(url)}")
    }

    private const val MAX_ATTEMPTS = 3

    private suspend fun backoff(attempt: Int): Long {
        val base = 500L * (1L shl (attempt - 1))
        return base.coerceAtMost(4000L) + (0L..200L).random()
    }

    /** Request dump for the debug log — the bearer token is shortened. */
    private fun requestDetail(url: String, headers: Headers, method: String, body: String?): String =
        buildString {
            appendLine("URL: $url")
            for ((k, v) in headers) {
                appendLine("$k: ${if (k.equals("Authorization", true)) shortToken(v) else v}")
            }
            if (body != null) append("Body: $body")
        }

    private fun responseDetail(url: String, code: Int, contentType: String?, text: String): String =
        buildString {
            appendLine("Status: $code · Content-Type: ${contentType ?: "?"} · ${text.length} bytes")
            append("Body: ")
            append(text.take(2500))
            if (text.length > 2500) append(" …(${text.length} bytes total)")
        }

    private fun shortToken(v: String): String {
        val t = v.removePrefix("Bearer ")
        return "Bearer ${t.take(30)}… (${t.length} chars, shortened for the log)"
    }

    private fun pathOf(url: String): String {
        val noQuery = url.substringBefore('?')
        val path = noQuery.substringAfter("wefeed-h5api-bff", noQuery)
        return path.ifEmpty { noQuery }
    }

    /**
     * Downloads raw bytes from a URL using the shared OkHttp client.
     * Used by NSFW detector for poster image downloads.
     */
    suspend fun downloadBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .headers(baseHeaders())
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@withContext null
            r.body?.bytes()
        }
    }

    /* ---------------------------------------------------------------- */
    /* Detail                                                           */
    /* ---------------------------------------------------------------- */

    suspend fun fetchDetail(slug: String): DetailData {
        val root = executeJson(
            "$API_BASE/detail?detailPath=${urlEnc(slug)}",
            baseHeaders(),
        )
        if (root.optInt("code") != 0) {
            throw IOException(
                "detail API code ${root.optInt("code")}: ${root.optString("message") ?: "not found"}"
            )
        }
        val data = root.optObj("data") ?: throw IOException("empty detail data")
        val subj = data.optObj("subject")
        if (subj?.optString("isForbid") == "true" || subj?.optString("isForbid") == "1") {
            throw IOException("This title is blocked/restricted.")
        }
        val d = normalizeDetail(data)
        val layout = when (val c = d.content) {
            is ContentLayout.Episodes -> "series — ${c.seasons.size} seasons, " +
                "${c.seasons.sumOf { it.maxEp }} episodes total"
            is ContentLayout.Parts -> "multi-part — ${c.items.size} parts"
        }
        AppLog.ok(
            AppLog.CAT_NET,
            "parsed detail: \"${d.title}\" [${d.type}] · $layout · ${d.dubs.size} dub languages",
            "subjectId=${d.subjectId} · type=${d.type} · layout=$layout\n" +
                "dubs: ${d.dubs.joinToString { "${it.lanName}(${if (it.original) "original" else "dub"})" }}",
        )
        return d
    }

    /* ---------------------------------------------------------------- */
    /* Play links + captions                                            */
    /* ---------------------------------------------------------------- */

    suspend fun fetchLinks(
        detailPath: String,
        subjectId: String,
        se: Int,
        ep: Int,
    ): PlayResult {
        val referer = "$SITE_ORIGIN/movies/$detailPath"
        val query = "subjectId=${urlEnc(subjectId)}&se=$se&ep=$ep" +
            "&detailPath=${urlEnc(detailPath)}&streamSignType=1&supportCodecs%5Bh264%5D=1"
        val root = executeJson("$PLAY_BASE/subject/play?$query", authHeaders(referer))
        if (root.optInt("code") != 0) {
            throw IOException(
                "play API code ${root.optInt("code")}: ${root.optString("message") ?: "error"}"
            )
        }

        val streams: MutableList<StreamItem> = mutableListOf()
        val rawStreams = root.optObj("data")?.optArr("streams") ?: emptyList()
        var vipFiltered = 0
        for (el in rawStreams) {
            val s = el as? JsonObject ?: continue
            val vipLocked = s.optString("vipLocked") == "true" || s.optString("vipLocked") == "1"
            val url = s.optString("url") ?: ""
            if (vipLocked || url.isEmpty()) {
                if (vipLocked) vipFiltered++
                continue
            }
            val resStr = s.optString("resolutions") ?: ""
            val size = s.optLong("size") ?: 0L
            streams.add(
                StreamItem(
                    format = s.optString("format") ?: "MP4",
                    id = s.optString("id") ?: "",
                    url = url,
                    resolution = resStr,
                    resolutionInt = resStr.toIntOrNull() ?: 0,
                    size = fmtSize(size),
                    sizeBytes = size,
                    duration = fmtDuration(s.optLong("duration")),
                )
            )
        }
        streams.sortBy { it.resolutionInt }

        // captions from the first available stream
        val captions: MutableList<CaptionItem> = mutableListOf()
        if (streams.isNotEmpty()) {
            try {
                captions.addAll(fetchCaptions(detailPath, subjectId, streams[0].id, referer))
            } catch (_: Exception) {
                /* subtitles are optional */
            }
        }
        AppLog.ok(
            AppLog.CAT_NET,
            "play parsed: ${streams.size} downloadable streams" +
                (if (vipFiltered > 0) ", $vipFiltered VIP-locked stream(s) hidden (site limit for guest users)" else "") +
                " · ${captions.size} subtitle languages",
            buildString {
                appendLine("request: se=$se ep=$ep · detailPath=$detailPath")
                streams.forEach { appendLine("stream: ${it.resolution}p · ${it.size} · ${it.format} · id=${it.id}") }
                if (vipFiltered > 0) appendLine("($vipFiltered stream(s) were vipLocked=true and were filtered out — this is the site's guest limit, not an app bug)")
                if (captions.isNotEmpty()) {
                    append("captions: ${captions.joinToString { "${it.lanName}(${it.size})" }}")
                }
            },
        )
        return PlayResult(streams, captions)
    }

    /** Subtitle (.srt) list for one stream. Public so the debug screen can
     *  test this endpoint on its own. */
    suspend fun fetchCaptions(
        detailPath: String,
        subjectId: String,
        streamId: String,
        referer: String = "$SITE_ORIGIN/movies/$detailPath",
    ): List<CaptionItem> {
        val cq = "format=MP4&id=${urlEnc(streamId)}" +
            "&subjectId=${urlEnc(subjectId)}&detailPath=${urlEnc(detailPath)}"
        val croot = executeJson("$API_BASE/subject/caption?$cq", authHeaders(referer))
        val rawCaps = croot.optObj("data")?.optArr("captions") ?: emptyList()
        val out: MutableList<CaptionItem> = mutableListOf()
        for (el in rawCaps) {
            val c = el as? JsonObject ?: continue
            val url = c.optString("url") ?: ""
            if (url.isEmpty()) continue
            out.add(
                CaptionItem(
                    lanName = c.optString("lanName") ?: "",
                    lan = c.optString("lan") ?: "",
                    url = url,
                    size = fmtSize(c.optLong("size")),
                )
            )
        }
        return out
    }

    /**
     * Downloads the first 1KB of a stream URL using the exact headers the
     * system DownloadManager sends (browser UA + site referer) — proves the
     * CDN link is actually fetchable end to end. Returns a human summary.
     */
    suspend fun probeCdn(url: String): String = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val req = Request.Builder().url(url)
            .header("User-Agent", MOBILE_UA)
            .header("Referer", "$SITE_ORIGIN/")
            .header("Range", "bytes=0-1023")
            .build()
        try {
            client.newCall(req).execute().use { r ->
                val ms = System.currentTimeMillis() - t0
                val ct = r.header("Content-Type") ?: "?"
                val cr = r.header("Content-Range") ?: "none"
                val summary = "HTTP ${r.code} · content-type: $ct · content-range: $cr"
                ApiDebug.record("GET", url, r.code, ms, r.isSuccessful, summary)
                AppLog.ok(
                    AppLog.CAT_NET,
                    "CDN probe ${hostOf(url)}: HTTP ${r.code} in $ms ms ($ct)",
                    "Range: bytes=0-1023 → ${r.code}\nContent-Type: $ct\nContent-Range: $cr",
                )
                when {
                    r.code == 206 -> "CDN OK — HTTP 206 Partial Content, Range honored ($ct)"
                    r.isSuccessful -> "CDN reachable — HTTP ${r.code} (Range ignored, but the file is downloadable, $ct)"
                    else -> "CDN problem — HTTP ${r.code}"
                }
            }
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - t0
            ApiDebug.record(
                "GET", url, null, ms, false,
                "${e.javaClass.simpleName}: ${e.message}",
            )
            AppLog.e(AppLog.CAT_NET, "CDN probe failed — ${e.javaClass.simpleName}: ${e.message ?: ""}")
            "CDN probe failed — ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /* ---------------------------------------------------------------- */
    /* Search                                                           */
    /* ---------------------------------------------------------------- */

    suspend fun search(keyword: String, page: Int = 1, perPage: Int = 24): List<SearchResult> {
        val root = executeJson(
            "$API_BASE/subject/search",
            authHeaders(),
            "POST",
            """{"keyword":${jsonStr(keyword)},"page":$page,"perPage":$perPage}""",
        )
        val items = root.optObj("data")?.optArr("items") ?: emptyList()
        return items.mapNotNull { el ->
            val i = el as? JsonObject ?: return@mapNotNull null
            val coverObj = i.get("cover")
            val cover = when (coverObj) {
                is JsonObject -> coverObj.optString("url") ?: ""
                is JsonPrimitive -> coverObj.content
                else -> ""
            }
            SearchResult(
                title = i.optString("title") ?: "",
                detailPath = i.optString("detailPath") ?: "",
                type = typeName(i.optInt("subjectType") ?: 0),
                cover = cover,
                year = (i.optString("releaseDate") ?: "").take(4),
                hasResource = i.optString("hasResource") == "true" || i.optString("hasResource") == "1",
                genres = (i.optString("genre") ?: "")
                    .split(',', ';')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
            )
        }
    }

    /* ---------------------------------------------------------------- */
    /* Slug parsing from pasted URLs                                    */
    /* ---------------------------------------------------------------- */

    fun resolveSlug(input: String): String? {
        if (input.isBlank()) return null
        var url = input.trim()
        if (!url.startsWith("http", ignoreCase = true)) {
            if (Regex("^[A-Za-z0-9_-]{4,}$").matches(url)) return url // bare slug
            url = "https://" + url.trimStart('/')
        }
        val path = try {
            URI(url).path ?: return null
        } catch (_: Exception) {
            return null
        }
        // /detail/{slug} or /movies/{slug} or /video/{extra}/{slug}
        val m = Regex("/(?:detail|movies|video)(?:/([^/]+))?/([A-Za-z0-9_-]+)").find(path)
        if (m != null) return m.groupValues[2]
        val m2 = Regex("/(?:detail|movies|video)/([A-Za-z0-9_-]+)").find(path)
        if (m2 != null) return m2.groupValues[1]
        return null
    }

    /* ---------------------------------------------------------------- */

    private fun urlEnc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private fun jsonStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun hostOf(url: String): String =
        try { URI(url).host ?: url } catch (_: Exception) { url }
}
