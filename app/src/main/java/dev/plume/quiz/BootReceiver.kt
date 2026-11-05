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
        // GrowQuiz: the boot question no longer hooks BOOT_COMPLETED (it never fired on some TVs).
        // MainActivity anchors it to the startup admin prompt's OK button instead.
    }
}
