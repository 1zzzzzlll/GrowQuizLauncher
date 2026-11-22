package dev.plume

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** Process-wide setup that must run before any activity or receiver: crash and diagnostics recording. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        CrashLog.install(this)
        // GrowQuiz: the guard asks the system for a foreground service, and on some firmwares that
        // request is only answered while one of our pages is on screen (see QuizGuardService.sync).
        // One place decides "visible", so the desktop, the quiz page and the phone-side settings all
        // count the same.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                dev.plume.quiz.QuizGuardService.uiVisible = true
            }

            override fun onActivityPaused(activity: Activity) {
                dev.plume.quiz.QuizGuardService.uiVisible = false
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        DiagLog.event("app_start", "", mapOf("vendor" to Vendor.current))
        // A process start is a chance to report an alarm whose quiz pop-up never appeared; the
        // audit no-ops when there is nothing overdue (e.g. the alarm that woke this very process).
        dev.plume.quiz.QuizAlarmReceiver.auditSwallowed(this)
        // The console must live with the process, not the activity: aggressive firmwares (a Huawei
        // vision screen) destroy the launcher as soon as another app opens, and the parents would
        // lose the console until someone walks to the TV again. Every process start — the
        // accessibility bind, the quiz alarm, the update check — brings it back. Idempotent.
        dev.plume.web.WebAdminServer.start(this)
        // GrowQuiz: a question must arrive at the minute the parents chose, hours after anyone last
        // touched the device. Every process start is a chance to put the guard back; it also takes
        // itself down when the quiz is switched off. No page is on screen yet, so on the firmware that
        // only answers a visible-page start this one is deferred to the desktop's first resume
        // (see QuizGuardService.sync).
        dev.plume.quiz.QuizGuardService.sync(this)
        // GrowQuiz: a reboot clears every alarm, and this firmware may never deliver BOOT_COMPLETED
        // (a Huawei vision screen stayed silent for 21 hours). The watchdog is a persisted job the
        // system restores by itself, so it is registered from every process start.
        dev.plume.quiz.QuizWatchdog.ensure(this)
    }
}
