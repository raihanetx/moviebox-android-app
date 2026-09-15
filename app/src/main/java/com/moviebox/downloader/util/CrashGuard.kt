package com.moviebox.downloader.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * CrashGuard — the app's "black box flight recorder".
 *
 * If the app ever crashes, Android normally just closes it and the details
 * are lost unless the developer happens to have USB debugging attached.
 * CrashGuard writes the crash (exception + stack trace) to a small file
 * right before the app dies. On the next start, App.kt picks the report up
 * and feeds it into AppLog as an ERROR event — so it is visible in the
 * Debug screen (ERRORS filter) and included in the share/export sheet.
 *
 * Rules:
 *  - recording a crash must itself NEVER crash (everything is try/caught)
 *  - only the LAST crash is kept (the file is overwritten + cleared)
 *  - after recording, the default system handler still runs so the user
 *    sees the normal "app stopped" behavior and the process dies cleanly.
 */
object CrashGuard {

    private const val FILE = "last_crash.txt"
    private const val MAX_CHARS = 8000

    /** Call once from Application.onCreate, before anything else. */
    fun install(context: Context) {
        val appContext = context.applicationContext
        // capture the current (system) handler BEFORE replacing it
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeReport(appContext, thread, throwable)
            } catch (_: Exception) {
                /* recording must never crash */
            }
            // hand over to the system default: show crash dialog, kill process
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * If the previous session ended in a crash, return the saved report
     * and clear it (one-shot). Returns null when the last session was clean.
     */
    fun takeLastCrashReport(context: Context): String? = try {
        val f = file(context)
        if (!f.exists()) null
        else f.readText().ifBlank { null }.also { f.delete() }
    } catch (_: Exception) {
        null
    }

    /* -------------------------------------------------------------- */

    private fun writeReport(context: Context, thread: Thread, t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        file(context).writeText(
            buildString {
                appendLine("time: ${System.currentTimeMillis()}")
                appendLine("thread: ${thread.name}")
                appendLine("exception: ${t.javaClass.name}: ${t.message}")
                appendLine()
                append(sw.toString().take(MAX_CHARS))
            }
        )
    }

    private fun file(context: Context): File = context.filesDir.resolve(FILE)
}
