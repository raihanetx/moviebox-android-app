package com.moviebox.downloader.debug

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ONE unified, always-on activity log for the whole app — a private
 * "logcat" that records every small thing that happens between opening
 * and closing the app:
 *
 *   process start, Activity lifecycle, screen navigation, user actions
 *   (chips / selections / buttons), every HTTP request with full request
 *   + response detail, JSON parsing results, download enqueues, progress
 *   milestones, completions and failures with reasons, history storage.
 *
 * - Live: StateFlow consumed by the Debug screen ("Live activity" tab).
 * - Persistent: appended to filesDir/applog.jsonl, one JSON object per
 *   line. The previous session's file is kept as applog-prev.jsonl so
 *   "what happened before I closed the app" is viewable next launch.
 * - Never throws: every persistence problem is swallowed (a logger that
 *   crashes the app would be worse than no logger).
 */
object AppLog {

    enum class Level { INFO, OK, WARN, ERROR }

    /* categories shown as filter chips in the debug UI */
    const val CAT_LIFE = "LIFE"   // app process / activity lifecycle
    const val CAT_NAV = "NAV"     // screen navigation (home/detail/downloads/debug)
    const val CAT_UI = "UI"       // user actions (search, chips, selections)
    const val CAT_NET = "NET"     // HTTP requests + parsing results
    const val CAT_DL = "DL"       // DownloadManager enqueues / progress / completion
    const val CAT_STORE = "STORE" // history.json persistence

    @Serializable
    private data class Persisted(
        val at: Long,
        val cat: String,
        val level: String,
        val msg: String,
        val detail: String = "",
    )

    data class Event(
        val id: Long,
        val at: Long,            // wall-clock millis
        val sinceStart: Long,    // ms since app process start
        val cat: String,
        val level: Level,
        val msg: String,
        val detail: String,
    )

    data class Stats(
        val startAt: Long,
        val events: Int,
        val netOk: Int,
        val netFailed: Int,
        val errors: Int,
    )

    private const val MAX_EVENTS = 2000
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    private val lock = Any()

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events.asStateFlow()

    private val _previous = MutableStateFlow<List<Event>>(emptyList())
    val previous: StateFlow<List<Event>> = _previous.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    private var nextId = 1L
    @Volatile private var startAt = System.currentTimeMillis()
    private var dir: File? = null
    private var logFile: File? = null
    private var netOk = 0
    private var netFailed = 0
    private var errors = 0
    private var writesSinceCheck = 0

    /** Called once from Application.onCreate — before anything else logs. */
    fun attach(context: Context) {
        synchronized(lock) {
            if (dir != null) return
            dir = context.filesDir
            val current = File(dir, "applog.jsonl")
            // keep the previous session readable, then start a fresh file
            _previous.value = if (current.exists()) {
                val prev = readEvents(current)
                val backup = File(dir, "applog-prev.jsonl")
                backup.delete()
                if (!current.renameTo(backup)) current.delete()
                prev
            } else {
                readEvents(File(dir, "applog-prev.jsonl"))
            }
            logFile = File(dir, "applog.jsonl")
            logFile?.delete()
            val ev = make(Level.INFO, CAT_LIFE, "App process started — logging everything from now until the app is closed")
            publish(ev)
        }
    }

    fun i(cat: String, msg: String, detail: String = "") = log(Level.INFO, cat, msg, detail)
    fun ok(cat: String, msg: String, detail: String = "") = log(Level.OK, cat, msg, detail)
    fun warn(cat: String, msg: String, detail: String = "") = log(Level.WARN, cat, msg, detail)
    fun e(cat: String, msg: String, detail: String = "") = log(Level.ERROR, cat, msg, detail)

    fun log(level: Level, cat: String, msg: String, detail: String = "") {
        synchronized(lock) { publish(make(level, cat, msg, detail)) }
    }

    /** Clears the CURRENT session log only (previous session is kept). */
    fun clear() {
        synchronized(lock) {
            _events.value = emptyList()
            netOk = 0
            netFailed = 0
            errors = 0
            logFile?.delete()
            logFile = dir?.let { File(it, "applog.jsonl") }
            publish(make(Level.INFO, CAT_LIFE, "Log cleared by user"))
        }
    }

    fun stats(): Stats = synchronized(lock) {
        Stats(
            startAt = startAt,
            events = _events.value.size,
            netOk = netOk,
            netFailed = netFailed,
            errors = errors,
        )
    }

    /** Full text export (for clipboard / Android share sheet). */
    fun export(header: String = "", includeDetails: Boolean = true): String = synchronized(lock) {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        buildString {
            if (header.isNotEmpty()) appendLine(header)
            appendLine("Session: started ${date(startAt)}, uptime ${uptime()}, " +
                "${_events.value.size} events, $netOk HTTP ok / $netFailed failed, $errors errors")
            appendLine()
            for (ev in _events.value) {
                appendLine("[+${"%.1f".format(ev.sinceStart / 1000.0)}s ${fmt.format(Date(ev.at))}] ${ev.cat} ${ev.level}  ${ev.msg}")
                if (includeDetails && ev.detail.isNotEmpty()) {
                    ev.detail.lines().forEach { appendLine("    $it") }
                }
            }
        }
    }

    fun exportPrevious(): String = synchronized(lock) {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        buildString {
            appendLine("Previous session log (${_previous.value.size} events, last line: " +
                "${_previous.value.lastOrNull()?.let { fmt.format(Date(it.at)) } ?: "?"})")
            for (ev in _previous.value) {
                appendLine("[${fmt.format(Date(ev.at))}] ${ev.cat} ${ev.level}  ${ev.msg}")
                if (ev.detail.isNotEmpty()) ev.detail.lines().forEach { appendLine("    $it") }
            }
        }
    }

    fun uptime(): String {
        val s = (System.currentTimeMillis() - startAt) / 1000
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    /* ---------------------------------------------------------------- */

    private fun make(level: Level, cat: String, msg: String, detail: String = ""): Event {
        val now = System.currentTimeMillis()
        return Event(
            id = nextId++,
            at = now,
            sinceStart = now - startAt,
            cat = cat,
            level = level,
            msg = msg.replace(Regex("\\s+"), " ").trim().take(300),
            detail = detail.take(4000),
        )
    }

    private fun publish(ev: Event) {
        _events.value = (_events.value + ev).takeLast(MAX_EVENTS)
        when {
            ev.cat == CAT_NET && ev.level == Level.OK -> netOk++
            ev.level == Level.ERROR -> { errors++; if (ev.cat == CAT_NET) netFailed++ }
        }
        persist(ev)
    }

    private fun persist(ev: Event) {
        val f = logFile ?: return
        try {
            f.appendText(json.encodeToString(
                Persisted(ev.at, ev.cat, ev.level.name, ev.msg, ev.detail)
            ) + "\n")
            if (++writesSinceCheck > 400) {
                writesSinceCheck = 0
                if (f.length() > MAX_FILE_BYTES) f.delete()
            }
        } catch (_: Exception) {
            /* logging must never crash the app */
        }
    }

    private fun readEvents(f: File): List<Event> = try {
        if (!f.exists()) emptyList()
        else f.readLines().mapIndexedNotNull { i, line ->
            try {
                val p = json.decodeFromString<Persisted>(line)
                Event(
                    id = i.toLong(),
                    at = p.at,
                    sinceStart = 0,
                    cat = p.cat,
                    level = runCatching { Level.valueOf(p.level) }.getOrDefault(Level.INFO),
                    msg = p.msg,
                    detail = p.detail,
                )
            } catch (_: Exception) {
                null
            }
        }.takeLast(1000)
    } catch (_: Exception) {
        emptyList()
    }

    private fun date(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
}
