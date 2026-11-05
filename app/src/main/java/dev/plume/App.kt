package dev.plume

import android.app.Application

/** Process-wide setup that must run before any activity or receiver: crash recording. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
