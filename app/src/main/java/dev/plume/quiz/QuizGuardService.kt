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
import dev.plume.Vendor

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
        ensureForeground()
    }

    /**
     * Every start request is answered here with a real foreground notification. A request that is
     * left unanswered makes the framework kill the whole process when its deadline passes — and
     * that kill takes an open quiz page and the video under it down as well.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ensureForeground()) {
            // The promotion failed: tear the service down now instead of waiting for the deadline.
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun ensureForeground(): Boolean {
        try {
            if (!started) {
                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "答题守护", NotificationManager.IMPORTANCE_MIN).apply {
                        description = "保持答题按时弹出"
                        setShowBadge(false)
                        setSound(null, null)
                    },
                )
            }
            // Repeated on every request on purpose: the system counts one deadline per request, and
            // startForeground() is what cancels it. Posting the same notification again is silent.
            startForeground(NOTIFICATION_ID, build())
            if (!started) {
                started = true
                runningForeground = true
                android.util.Log.i("GrowQuiz", "quiz guard entered foreground")
            }
            return true
        } catch (e: Exception) {
            // START_STICKY stays off until the notification is really up.
            runningForeground = false
            android.util.Log.e("GrowQuiz", "quiz guard foreground failed", e)
            DiagLog.event("quiz_guard_fail", "start:" + (e.message ?: e.javaClass.simpleName))
            return false
        }
    }

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

    override fun onDestroy() {
        runningForeground = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "quiz_guard"
        private const val NOTIFICATION_ID = 7

        /**
         * True while this process holds the guard. A start request is only made when it is false:
         * the desktop syncs on every resume, and a start per resume would be noise for the system.
         */
        @Volatile private var runningForeground = false

        /**
         * True while one of our activities is on screen, kept by [dev.plume.App]. A foreground-service
         * start from a visible app is a different request for the firmware than one from the
         * background, and on TCL it is the difference between a guard and a dead process.
         */
        @Volatile var uiVisible = false

        /**
         * Starts the guard when the quiz is on, stops it when the parents switch the quiz off. Safe to
         * call from anywhere and as often as wanted: a second start is a no-op for the system.
         */
        fun sync(context: Context) {
            val app = context.applicationContext
            val prefs = QuizPrefs(app)
            val session = QuizLock.sessionActive(
                quizActive = prefs.quizActive,
                restGuard = prefs.restGuard,
                restUntil = prefs.restUntil,
                now = System.currentTimeMillis(),
                restForced = prefs.restForced,
            )
            if (!prefs.enabled && !session) {
                // Also remove an older full-screen reminder: tapping it later must not reopen a page
                // after the parent used the home-page switch to stop the current session.
                runningForeground = false
                runCatching {
                    app.getSystemService(NotificationManager::class.java)?.cancel(QuizAlarmReceiver.NOTIFICATION_ID)
                }
                runCatching { app.stopService(Intent(app, QuizGuardService::class.java)) }
                return
            }
            if (runningForeground) return
            val intent = Intent(app, QuizGuardService::class.java)
            val start = QuizLock.guardStart(visible = uiVisible, tcl = Vendor.isTcl())
            if (start == QuizLock.GuardStart.SKIP) {
                // GrowQuiz: this firmware (TCL, Android 11, seen on tcl_mt5879_cn) answers a foreground
                // start made from the background with silence: the service is never created, the request
                // keeps its countdown, and 20 s later the system kills the whole app with
                // "startForegroundService() did not then call Service.startForeground()". The kill takes
                // a live quiz page and the video under it. So while nothing of ours is on screen, ask for
                // nothing: the next visible page (the question, the desktop) starts the guard, and the
                // alarm and the persisted watchdog wake the process in the meantime.
                noteDeferred()
                return
            }
            try {
                if (start == QuizLock.GuardStart.PLAIN) {
                    // A plain start: the app is in front, so this is allowed, and it arms no deadline
                    // — a promotion that the firmware drops cannot turn into a killed process.
                    app.startService(intent)
                } else {
                    app.startForegroundService(intent)
                }
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

        /** The guard was wanted in the background and left alone. Reported at most once an hour. */
        private fun noteDeferred() {
            val now = System.currentTimeMillis()
            if (now - lastDeferAt <= FAIL_LOG_GAP_MS) return
            lastDeferAt = now
            DiagLog.event("quiz_guard_deferred", "background")
        }

        private const val FAIL_LOG_GAP_MS = 3_600_000L
        private var lastFailAt = 0L
        private var lastDeferAt = 0L
    }
}
