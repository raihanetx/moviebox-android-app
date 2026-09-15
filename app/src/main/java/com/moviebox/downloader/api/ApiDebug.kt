package com.moviebox.downloader.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global, always-on record of every HTTP exchange the app makes.
 *
 * Two consumers:
 *  1. Debug screen "LIVE API LOG" — shows all app traffic as the user
 *     searches / opens titles / downloads.
 *  2. The end-to-end test — wraps groups of calls into labeled steps and
 *     captures their raw request/response for on-device diagnosis.
 *
 * Thread-safe: exchanges arrive from Dispatchers.IO workers.
 */
object ApiDebug {

    data class Exchange(
        val id: Long,
        val at: Long,              // wall-clock millis
        val step: String,          // "app" while no test step is active
        val method: String,
        val url: String,           // host + path + trimmed query
        val httpCode: Int?,        // null => request never got a response
        val ms: Long,
        val ok: Boolean,
        val snippet: String,       // response head (or error text), capped
    )

    const val STEP_APP = "app"

    private val lock = Any()

    private val _exchanges = MutableStateFlow<List<Exchange>>(emptyList())
    val exchanges: StateFlow<List<Exchange>> = _exchanges.asStateFlow()

    private var nextId = 1L
    private var stepLabel: String = STEP_APP
    private var capturing = false
    private val stepBuf = ArrayDeque<Exchange>()

    /** Start capturing exchanges of the next calls into a labeled step. */
    fun beginStep(label: String) = synchronized(lock) {
        stepLabel = label
        capturing = true
        stepBuf.clear()
    }

    /** Stop capturing; returns the exchanges recorded for this step. */
    fun endStep(): List<Exchange> = synchronized(lock) {
        capturing = false
        stepLabel = STEP_APP
        stepBuf.toList()
    }

    fun clear() = synchronized(lock) {
        _exchanges.value = emptyList()
    }

    fun record(
        method: String,
        url: String,
        httpCode: Int?,
        ms: Long,
        ok: Boolean,
        snippet: String,
    ) {
        val ex = Exchange(
            id = nextId++,
            at = System.currentTimeMillis(),
            step = synchronized(lock) { stepLabel },
            method = method,
            url = tidyUrl(url),
            httpCode = httpCode,
            ms = ms,
            ok = ok,
            // collapse whitespace so raw JSON bodies don't explode in the UI
            snippet = snippet.replace(Regex("\\s+"), " ").take(4000),
        )
        synchronized(lock) {
            if (capturing) stepBuf.addLast(ex)
            _exchanges.value = (_exchanges.value + ex).takeLast(300)
        }
    }

    /** Signed CDN URLs are enormous — keep host/path plus a short query head. */
    private fun tidyUrl(u: String): String {
        val q = u.indexOf('?')
        if (q < 0) return u
        val head = u.substring(0, q)
        val query = u.substring(q + 1)
        return if (query.length > 160) "$head?${query.take(160)}…" else "$head?$query"
    }
}
