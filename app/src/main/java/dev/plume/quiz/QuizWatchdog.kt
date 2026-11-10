package dev.plume.quiz

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import dev.plume.DiagLog

/**
 * Puts the quiz alarm back after a reboot that nobody told the app about.
 *
 * A reboot clears every alarm. The app re-schedules from BOOT_COMPLETED, but some firmwares never
 * deliver that broadcast to a third-party app — a Huawei vision screen sat silent for 21 hours after
 * a reboot and only recovered when someone opened the app by hand. A job that the system itself
 * persists is restored at boot without the app receiving anything, so this is the way back in that
 * does not depend on the firmware's goodwill.
 *
 * It is a watchdog, not a scheduler: it only acts when the alarm it can see is wrong or gone
 * (see [QuizLock.alarmLost]), so a healthy schedule is never pushed forward.
 */
class QuizWatchdog : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val ctx = applicationContext
        try {
            val prefs = QuizPrefs(ctx)
            if (prefs.enabled) {
                val now = System.currentTimeMillis()
                val uptime = SystemClock.elapsedRealtime()
                val due = prefs.nextTriggerAt
                if (QuizLock.alarmLost(now, now - uptime, due, prefs.lastScheduleAt)) {
                    QuizScheduler.reschedule(ctx)
                    // lateMin: how long the due time was already behind. 0 means the alarm was only
                    // cleared by the reboot and the quiz is not late yet.
                    val late = if (due in 1..now) (now - due) / 60_000L else 0L
                    DiagLog.event(
                        "alarm_restored", "",
                        mapOf("lateMin" to late.toString(), "uptimeMin" to (uptime / 60_000L).toString()),
                    )
                }
                // The guard is what keeps the process alive until the next question; after a reboot
                // nothing else has started it (see QuizGuardService).
                QuizGuardService.sync(ctx)
            }
        } catch (e: Exception) {
            DiagLog.event("alarm_restore_fail", e.message ?: e.javaClass.simpleName)
        }
        jobFinished(params, false)
        return true
    }

    /** True: a periodic job must ask to be kept, or the system drops it after this run. */
    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val JOB_ID = 4108

        /** Android's floor for a periodic job. A missed quiz can be up to this late; never lost. */
        private const val PERIOD_MS = 15 * 60 * 1000L

        /**
         * Registers the watchdog once. Persisted, so the system restores it after every reboot even
         * when BOOT_COMPLETED never reaches the app. Safe to call from anywhere and as often as
         * wanted: an existing job is left alone, so its period is not restarted on every process start.
         */
        fun ensure(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (js.allPendingJobs.any { it.id == JOB_ID }) return
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, QuizWatchdog::class.java))
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build()
            try {
                js.schedule(info)
            } catch (e: Exception) {
                DiagLog.event("alarm_restore_fail", "job:" + (e.message ?: e.javaClass.simpleName))
            }
        }
    }
}
