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

    /** Narration belongs only to a visible, interactive page; a dark or covered screen stays silent. */
    fun shouldNarrate(screenInteractive: Boolean, pageForeground: Boolean): Boolean =
        screenInteractive && pageForeground

    /** Both anti-guess rest and a parent-started rest count; either owns the screen until it expires. */
    fun restOwed(restGuard: Boolean, restForced: Boolean, restUntil: Long, now: Long): Boolean =
        (restGuard || restForced) && restUntil > now

    /** An open question or an active rest owns Home. Disabling the quiz clears quizActive in QuizPrefs. */
    fun sessionActive(quizActive: Boolean, restGuard: Boolean, restUntil: Long, now: Long, restForced: Boolean = false): Boolean =
        quizActive || restOwed(restGuard, restForced, restUntil, now)

    /**
     * Whether this delivery is the same alarm arriving twice.
     *
     * Some firmwares hand one alarm broadcast to the app two times in the same second: the TCL test
     * set wrote two alarm_fired lines per wake-up, the second one carrying a due time already 10
     * minutes ahead — proof it ran after the first had rescheduled. The shortest parent interval is
     * one minute, so two deliveries a few seconds apart are never two alarms.
     */
    fun duplicateAlarm(lastAlarmAt: Long, now: Long): Boolean =
        lastAlarmAt > 0L && now - lastAlarmAt in 0 until DUPLICATE_MS

    /** Deliveries closer than this belong to the same alarm; see [duplicateAlarm]. */
    const val DUPLICATE_MS = 3_000L

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
     * Grace window after a due time before the watchdog treats the alarm as missing.
     *
     * Without a grace margin, a watchdog tick that lands milliseconds after the due time races
     * with AlarmManager's own broadcast: the watchdog reschedules 15 minutes ahead, and 19 ms
     * later the real alarm fires and reads the newly pushed due time as a huge negative lateMs.
     */
    const val OVERDUE_GRACE_MS = 60_000L

    /**
     * Whether the quiz alarm is gone and must be set again.
     *
     * A reboot clears every alarm in the system. The app normally re-schedules from BOOT_COMPLETED, but
     * some firmwares never deliver that broadcast to a third-party app: a Huawei vision screen stayed
     * silent for 21 hours after a reboot and only came back when someone opened it by hand. Two cases
     * need a new alarm:
     *  - the time it was due has passed by more than [graceMs] (nothing fired), or
     *  - it was last set before this boot, so the reboot cleared it.
     *
     * [bootAt] is now minus elapsed realtime, i.e. when this uptime began.
     */
    fun alarmLost(
        now: Long,
        bootAt: Long,
        nextTriggerAt: Long,
        lastScheduleAt: Long,
        graceMs: Long = OVERDUE_GRACE_MS,
    ): Boolean {
        if (nextTriggerAt <= 0L) return true // nothing is due: one has to be scheduled
        if (nextTriggerAt <= now - graceMs) return true // overdue past grace and never fired
        return lastScheduleAt < bootAt // set before this boot: the reboot cleared it
    }

    /**
     * Whether the watchdog should open an overdue quiz right now instead of only pushing the next
     * alarm further into the future.
     *
     * Field reports from Hisense VIDAA (13 consecutive watchdog restores in 14 h with zero
     * alarm_fired) and Konka V812 (3 restores while awake) showed firmwares that silently swallow
     * AlarmManager.setAlarmClock while still running persisted JobScheduler ticks. Catching up is
     * only safe when the missed alarm was armed during THIS boot, is past [graceMs], the screen is
     * on, and no quiz or rest session is already active.
     */
    fun watchdogCatchUp(
        now: Long,
        bootAt: Long,
        nextTriggerAt: Long,
        lastScheduleAt: Long,
        screenInteractive: Boolean,
        sessionActive: Boolean,
        graceMs: Long = OVERDUE_GRACE_MS,
    ): Boolean =
        screenInteractive &&
            !sessionActive &&
            nextTriggerAt > 0L &&
            now - nextTriggerAt >= graceMs &&
            lastScheduleAt >= bootAt

    /**
     * Whether an alarm or parent command should call startActivity for QuizActivity.
     *
     * When the question page is already visibly in front of the child and this is a routine alarm
     * (not a parent-triggered forced rest), relaunching singleTask QuizActivity causes an
     * onPause -> onNewIntent -> onResume bounce that trips the snap-back watchdog (124 fake
     * snapback_ok + 249 quiz_open on a Xiaomi TV in one night).
     */
    fun shouldRelaunchForAlarm(quizForeground: Boolean, resting: Boolean): Boolean =
        resting || !quizForeground

    /**
     * Whether an onPause was caused by our own internal intent to QuizActivity (e.g. a parent rest
     * or HomeOverride bringing the quiz forward), rather than the child escaping to another app.
     */
    fun isExpectedSelfPause(expectedAtMs: Long, nowMs: Long, windowMs: Long = 2_000L): Boolean =
        expectedAtMs > 0L && nowMs - expectedAtMs in 0..windowMs

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

    /** How the answer guard asks the system for a foreground service; see [guardStart]. */
    enum class GuardStart { SKIP, PLAIN, FOREGROUND }

    /**
     * Which start the guard may make.
     *
     * A foreground-service request is a request the system counts down, and a request that is never
     * answered ends with the whole process killed — an open quiz page included. One firmware (TCL,
     * Android 11, seen on tcl_mt5879_cn) does not answer one made from the background at all. So a
     * visible page uses a plain start, which arms no countdown; the same firmware gets no request at
     * all while nothing of ours is on screen; every other firmware keeps the background start it
     * needs to stay alive between questions.
     */
    fun guardStart(visible: Boolean, tcl: Boolean): GuardStart = when {
        visible -> GuardStart.PLAIN
        tcl -> GuardStart.SKIP
        else -> GuardStart.FOREGROUND
    }
}
