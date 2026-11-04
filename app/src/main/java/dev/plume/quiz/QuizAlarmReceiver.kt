package dev.plume.quiz

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class QuizAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!QuizPrefs(context).enabled) return
        // Schedule first so an unanswered or interrupted quiz never disables future sessions.
        QuizScheduler.reschedule(context)
        context.startActivity(
            Intent(context, QuizActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            ),
        )
    }
}
