package com.moviebox.downloader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.moviebox.downloader.debug.AppLog
import com.moviebox.downloader.ui.MovieBoxTheme
import com.moviebox.downloader.ui.MovieBoxApp

class MainActivity : ComponentActivity() {

    private var created = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        created = System.currentTimeMillis()
        AppLog.i(
            AppLog.CAT_LIFE,
            "MainActivity.onCreate — ${if (savedInstanceState == null) "cold start (fresh launch)" else "re-created (config change or process restored)"}",
        )
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MovieBoxTheme {
                MovieBoxApp()
            }
        }
        AppLog.ok(AppLog.CAT_LIFE, "UI ready — first frame composed in ${System.currentTimeMillis() - created} ms")
    }

    override fun onStart() {
        super.onStart()
        AppLog.i(AppLog.CAT_LIFE, "MainActivity.onStart — app visible")
    }

    override fun onResume() {
        super.onResume()
        AppLog.ok(AppLog.CAT_LIFE, "MainActivity.onResume — app in foreground and interactive (uptime ${AppLog.uptime()})")
    }

    override fun onPause() {
        super.onPause()
        AppLog.i(AppLog.CAT_LIFE, "MainActivity.onPause — app losing focus (background work/downloads may continue)")
    }

    override fun onStop() {
        super.onStop()
        AppLog.i(
            AppLog.CAT_LIFE,
            "MainActivity.onStop — app in background. Downloads continue via the system DownloadManager; the log keeps recording them",
        )
    }

    override fun onDestroy() {
        AppLog.i(AppLog.CAT_LIFE, "MainActivity.onDestroy — activity destroyed. Log saved to applog.jsonl, viewable next launch")
        super.onDestroy()
    }
}
