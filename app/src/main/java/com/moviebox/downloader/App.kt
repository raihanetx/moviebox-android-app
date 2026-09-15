package com.moviebox.downloader

import android.app.Application
import com.moviebox.downloader.debug.AppLog

/** attaches the global activity log before anything else runs */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.attach(this)
    }
}
