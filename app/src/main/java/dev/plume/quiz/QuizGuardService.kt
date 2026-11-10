package dev.plume.quiz

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import dev.plume.DiagLog
import dev.plume.MainActivity

/**
 * Keeps the process alive so the quiz alarm can do its job.
 *
 * A question must arrive at the minute the parents chose, hours after anyone last touched the
 * device. Ordinary apps are frozen or killed long before that: on phones the maker's own power
 * manager does it (MIUI, ColorOS and EMUI are the aggressive ones reported so far), and a killed
 * process cannot start an activity. A foreground service is the one kind of process the system
 * keeps, and its notification also tells the parent the guard is on.
 *
 * It does no work of its own: the alarm does the scheduling and [QuizActivity] does the locking.
 * This only makes sure there is a process for them to run in — the difference between a question
 * that appears and a `quiz_popup_blocked` in the report.
 */
class QuizGuardService : Service() {

    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "答题守护", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "保持答题按时弹出"
                    setShowBadge(false)
                    setSound(null, null)
                },
            )
            startForeground(NOTIFICATION_ID, build())
            started = true
        } catch (e: Exception) {
            // A firmware that refuses a foreground service of this type would otherwise crash, and
            // START_STICKY would bring the crash back in a loop. Give up instead: the alarm still
            // works, only without the protection of a live process.
            DiagLog.event("quiz_guard_fail", "start:" + (e.message ?: e.javaClass.simpleName))
            stopSelf()
        }
    }

    /**
     * START_STICKY only once the notification is up: if [onCreate] gave up, a sticky restart would
     * loop the same failure instead of leaving the alarm alone.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        if (started) START_STICKY else START_NOT_STICKY

    private fun build(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("答题守护中")
            .setContentText("到点会弹出题目")
            .setContentIntent(open)
            .setOngoing(true) // swiping it away would take the guard with it
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val CHANNEL = "quiz_guard"
        private const val NOTIFICATION_ID = 7

        /**
         * Starts the guard when the quiz is on, stops it when the parents switch the quiz off. Safe to
         * call from anywhere and as often as wanted: a second start is a no-op for the system.
         */
        fun sync(context: Context) {
            val app = context.applicationContext
            if (!QuizPrefs(app).enabled) {
                runCatching { app.stopService(Intent(app, QuizGuardService::class.java)) }
                return
            }
            try {
                app.startForegroundService(Intent(app, QuizGuardService::class.java))
            } catch (e: Exception) {
                // Android 12 and newer refuse a start from the background unless the caller is exempt
                // (boot, exact alarm, the user opening the app). The next such moment tries again. The
                // failure is reported at most once an hour: a process woken by an alarm while the guard
                // is down would otherwise write the same line on every wake-up.
                val now = System.currentTimeMillis()
                if (now - lastFailAt > FAIL_LOG_GAP_MS) {
                    lastFailAt = now
                    DiagLog.event("quiz_guard_fail", e.message ?: e.javaClass.simpleName)
                }
            }
        }

        private const val FAIL_LOG_GAP_MS = 3_600_000L
        private var lastFailAt = 0L
    }
}
