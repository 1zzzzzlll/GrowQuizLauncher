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
    }
}
