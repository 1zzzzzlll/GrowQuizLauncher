package dev.plume.quiz

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores the next quiz after reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            QuizDeviceOwner.configure(context)
            QuizScheduler.reschedule(context)
        }
        // A power-on with the boot quiz on owes one question; MainActivity shows it as soon as it comes up.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = QuizPrefs(context)
            if (prefs.bootQuiz) prefs.bootPending = true
        }
    }
}
