package dev.plume.quiz

/**
 * When the quiz page must take the screen back, and how often to try.
 *
 * Kept off Android so the rules can be tested: getting them wrong is either a child escaping the
 * question or a phone that spends the afternoon relaunching an activity.
 */
object QuizLock {

    /** The first attempt is quick: the page was just covered and the child is still looking at the screen. */
    const val FIRST_MS = 400L

    /** Retries back off, so a page that cannot come back (permission missing) does not spin. */
    const val MAX_RETRY_MS = 4_000L

    /**
     * Delay before attempt number [attempt] (0 = first). Doubles from [FIRST_MS], capped.
     * 0 → 400 ms, 1 → 800, 2 → 1600, 3 → 3200, 4 and later → 4000.
     */
    fun retryDelay(attempt: Int): Long {
        if (attempt <= 0) return FIRST_MS
        var delay = FIRST_MS
        repeat(attempt) {
            // Checked before doubling: a large attempt would overflow Long and come back negative.
            if (delay >= MAX_RETRY_MS) return MAX_RETRY_MS
            delay *= 2
        }
        return delay.coerceAtMost(MAX_RETRY_MS)
    }

    /**
     * Whether a lost foreground is something to take back.
     *
     * A solved question, a finished page and a screen that is off are all reasons to stay put: the
     * child is done, or nobody is looking. Lock-task mode needs no help either — the system holds the
     * screen. Everything else is an escape and gets taken back.
     */
    fun shouldReclaim(solved: Boolean, pageAlive: Boolean, screenInteractive: Boolean, lockTaskActive: Boolean): Boolean =
        !solved && pageAlive && screenInteractive && !lockTaskActive

    /**
     * Whether the quiz alarm is gone and must be set again.
     *
     * A reboot clears every alarm in the system. The app normally re-schedules from BOOT_COMPLETED, but
     * some firmwares never deliver that broadcast to a third-party app: a Huawei vision screen stayed
     * silent for 21 hours after a reboot and only came back when someone opened it by hand. Two cases
     * need a new alarm:
     *  - the time it was due has passed (nothing fired), or
     *  - it was last set before this boot, so the reboot cleared it.
     *
     * [bootAt] is now minus elapsed realtime, i.e. when this uptime began.
     */
    fun alarmLost(now: Long, bootAt: Long, nextTriggerAt: Long, lastScheduleAt: Long): Boolean {
        if (nextTriggerAt <= 0L) return true // nothing is due: one has to be scheduled
        if (nextTriggerAt <= now) return true // due and never fired
        return lastScheduleAt < bootAt // set before this boot: the reboot cleared it
    }

    /**
     * The permissions the "answer on time" promise rests on, in the order the console shows them.
     * Each one is granted by the parent on the device; none of them needs a computer.
     */
    enum class Need { OVERLAY, EXACT_ALARM, BATTERY, NOTIFICATION, FULL_SCREEN }

    /**
     * Which needs are still open. [sdk] gates the ones that only exist on newer Android: exact alarms
     * arrived with 12, the notification permission with 13, and the full-screen-intent switch — the one
     * that lets an alarm open a page over another app — with 14. A need the platform cannot ask for is
     * never reported, so the list never shows an item that cannot be fixed.
     */
    fun missing(
        sdk: Int,
        overlay: Boolean,
        exactAlarm: Boolean,
        batteryFree: Boolean,
        notifications: Boolean,
        fullScreen: Boolean,
    ): List<Need> {
        val out = ArrayList<Need>(5)
        if (!overlay) out += Need.OVERLAY
        if (sdk >= 31 && !exactAlarm) out += Need.EXACT_ALARM
        if (!batteryFree) out += Need.BATTERY
        if (sdk >= 33 && !notifications) out += Need.NOTIFICATION
        if (sdk >= 34 && !fullScreen) out += Need.FULL_SCREEN
        return out
    }

    /** True when every permission the platform can grant has been granted. */
    fun ready(
        sdk: Int,
        overlay: Boolean,
        exactAlarm: Boolean,
        batteryFree: Boolean,
        notifications: Boolean,
        fullScreen: Boolean,
    ): Boolean = missing(sdk, overlay, exactAlarm, batteryFree, notifications, fullScreen).isEmpty()
}
