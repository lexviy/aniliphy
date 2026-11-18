package com.aniliphy

import android.app.Application
import com.aniliphy.core.CrashLog

class AniliphyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // First, so a crash anywhere (the app, the widget, the background checks) is kept.
        CrashLog.install(this)
        com.aniliphy.core.Incognito.init(this)
    }
}
