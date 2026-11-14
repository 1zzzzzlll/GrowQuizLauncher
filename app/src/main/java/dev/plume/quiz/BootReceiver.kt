package dev.plume.quiz

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.plume.DiagLog

/** Restores the next quiz after reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            QuizDeviceOwner.configure(context)
            QuizScheduler.reschedule(context)
            // A boot is one of the few moments the system allows a foreground service to start from
            // the background, so the guard is put back here rather than waiting for someone to open
            // the app: see QuizGuardService.
            QuizGuardService.sync(context)
            // Same reason: register the watchdog on the one boot path this firmware does deliver.
            QuizWatchdog.ensure(context)
        }
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // GrowQuiz: some TVs never deliver this broadcast (TCL's boot manager holds it), so this
            // event firing at all is already a fact worth reporting about the firmware.
            DiagLog.event("boot_completed")
            // GrowQuiz: a power cut in the middle of a question or a forced rest keeps quizActive /
            // restUntil set; bring that page back. The same restore also runs when the Home service
            // binds after a boot. On Android 10+ the start is dropped without the "display over
            // other apps" grant — the service-bound path is the primary one.
            val prefs = QuizPrefs(context)
            if (QuizLock.sessionActive(
                    quizActive = prefs.quizActive,
                    restGuard = prefs.restGuard,
                    restUntil = prefs.restUntil,
                    now = System.currentTimeMillis(),
                    restForced = prefs.restForced,
                )) {
                runCatching {
                    context.startActivity(
                        Intent(context, QuizActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure { DiagLog.event("boot_quiz_restore_fail", it.message ?: "") }
            }
        }
        // GrowQuiz: the boot question no longer hooks BOOT_COMPLETED (it never fired on some TVs).
        // MainActivity anchors it to the startup admin prompt's OK button instead.
    }
}
