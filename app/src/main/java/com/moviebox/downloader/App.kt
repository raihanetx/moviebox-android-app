package com.moviebox.downloader

import android.app.Application
import com.moviebox.downloader.debug.AppLog
import com.moviebox.downloader.util.CrashGuard

/** attaches the global activity log before anything else runs */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // crash black box first, so even a crash during startup is recorded
        CrashGuard.install(this)
        AppLog.attach(this)
        // if the previous session crashed, surface the report in the Debug screen
        CrashGuard.takeLastCrashReport(this)?.let { report ->
            AppLog.e(AppLog.CAT_LIFE, "App crashed last session — crash report:", report)
        }
    }
}
