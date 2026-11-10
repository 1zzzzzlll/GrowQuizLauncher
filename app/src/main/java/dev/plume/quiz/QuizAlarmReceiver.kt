package dev.plume.quiz

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import dev.plume.DiagLog
import dev.plume.Vendor

class QuizAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = QuizPrefs(context)
        if (!prefs.enabled) return
        // GrowQuiz: read the due time before rescheduling overwrites it, so the event can say how
        // late the alarm really was — the number that tells an inexact fallback from a broken alarm.
        val due = prefs.nextTriggerAt
        // Schedule first so an unanswered or interrupted quiz never disables future sessions.
        QuizScheduler.reschedule(context)
        // GrowQuiz: the alarm wakes this process anyway, so a TV nobody opens still reports on
        // schedule — day-one data showed most devices only ever started once.
        dev.plume.ReportUpload.maybeUpload(context)
        // GrowQuiz: judge the previous alarm before this one overwrites its timestamp — a fired
        // alarm that never produced a quiz_open was swallowed silently (background-start rules
        // throw nothing), and only this look-back can see it.
        auditSwallowed(context)
        prefs.lastAlarmAt = System.currentTimeMillis()
        // GrowQuiz: the report pairs this event with QuizActivity's quiz_open; an alarm_fired with
        // no quiz_open soon after means the firmware swallowed the start (background-start rules).
        val kv = mutableMapOf<String, String>()
        if (due > 0) kv["lateMs"] = (System.currentTimeMillis() - due).toString()
        DiagLog.event("alarm_fired", "", kv)
        try {
            context.startActivity(
                Intent(context, QuizActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                ),
            )
        } catch (e: Exception) {
            DiagLog.event("quiz_popup_blocked", e.message ?: e.javaClass.simpleName)
        }
        // The start above throws nothing when Android silently drops a background start, so it is not
        // enough on its own. A full-screen intent is the way an alarm is allowed to reach the screen
        // from the background on Android 10 and newer, whatever the app on screen is doing.
        notifyFullScreen(context)
    }

    /**
     * Shows the question page as an alarm, not as a notification. The system opens the page over
     * whatever is running, which is the one path the background-start rules do not block.
     */
    private fun notifyFullScreen(context: Context) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "答题提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "到点弹出答题页面"
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
            val open = PendingIntent.getActivity(
                context, 2,
                Intent(context, QuizActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("该答题了")
                .setContentText("答对一题后即可继续")
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .setContentIntent(open)
                .setFullScreenIntent(open, true) // the page opens itself; the notification is the fallback
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            DiagLog.event("quiz_popup_blocked", "fullscreen:" + (e.message ?: e.javaClass.simpleName))
        }
    }

    companion object {
        private const val CHANNEL = "quiz_alarm"

        /** Shared with QuizActivity, which cancels the alarm notification once the page is on screen. */
        const val NOTIFICATION_ID = 9
        /**
         * Writes quiz_popup_blocked("silent") when the last alarm on record never produced a
         * quiz_open. Called from here and from App.onCreate, so a TV whose alarms stop entirely
         * still reports the miss the next time any part of the app runs.
         */
        fun auditSwallowed(context: Context) {
            val prefs = QuizPrefs(context)
            val now = System.currentTimeMillis()
            val missed = PopupAudit.swallowed(
                prefs.lastAlarmAt, prefs.lastQuizOpenAt, prefs.popupLoggedFor, now,
            ) ?: return
            prefs.popupLoggedFor = missed
            DiagLog.event(
                "quiz_popup_blocked",
                "silent",
                mapOf(
                    "vendor" to Vendor.current,
                    "overlay" to Settings.canDrawOverlays(context).toString(),
                    "sdk" to Build.VERSION.SDK_INT.toString(),
                    "ageMin" to ((now - missed) / 60_000L).toString(),
                ),
            )
        }
    }
}
