package dev.plume.quiz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuizLockTest {

    @Test
    fun `the first retry is quick and later ones back off`() {
        assertEquals(QuizLock.FIRST_MS, QuizLock.retryDelay(0))
        assertEquals(800L, QuizLock.retryDelay(1))
        assertEquals(1_600L, QuizLock.retryDelay(2))
        assertEquals(3_200L, QuizLock.retryDelay(3))
    }

    @Test
    fun `retries never wait longer than the cap`() {
        assertEquals(QuizLock.MAX_RETRY_MS, QuizLock.retryDelay(4))
        assertEquals(QuizLock.MAX_RETRY_MS, QuizLock.retryDelay(2_000))
    }

    // A negative attempt can only come from a caller mistake; it must still yield a usable delay.
    @Test
    fun `a negative attempt behaves like the first`() {
        assertEquals(QuizLock.FIRST_MS, QuizLock.retryDelay(-1))
    }

    @Test
    fun `an escape is taken back`() {
        assertTrue(QuizLock.shouldReclaim(solved = false, pageAlive = true, screenInteractive = true, lockTaskActive = false))
    }

    @Test
    fun `a solved question is left alone`() {
        assertFalse(QuizLock.shouldReclaim(solved = true, pageAlive = true, screenInteractive = true, lockTaskActive = false))
    }

    // Screen off: the child is not looking at anything, so pulling the page back would only wake the
    // device for nobody.
    @Test
    fun `a dark screen is left alone`() {
        assertFalse(QuizLock.shouldReclaim(solved = false, pageAlive = true, screenInteractive = false, lockTaskActive = false))
    }

    @Test
    fun `lock task needs no help`() {
        assertFalse(QuizLock.shouldReclaim(solved = false, pageAlive = true, screenInteractive = true, lockTaskActive = true))
    }

    @Test
    fun `a dying page does not try to come back`() {
        assertFalse(QuizLock.shouldReclaim(solved = false, pageAlive = false, screenInteractive = true, lockTaskActive = false))
    }

    // Android 8: only the overlay permission exists to ask for.
    @Test
    fun `older levels are only asked for what they have`() {
        assertEquals(
            listOf(QuizLock.Need.OVERLAY),
            QuizLock.missing(sdk = 26, overlay = false, exactAlarm = false, batteryFree = true, notifications = false, fullScreen = false),
        )
        assertEquals(
            listOf(QuizLock.Need.OVERLAY, QuizLock.Need.EXACT_ALARM),
            QuizLock.missing(sdk = 31, overlay = false, exactAlarm = false, batteryFree = true, notifications = false, fullScreen = false),
        )
        assertEquals(
            listOf(QuizLock.Need.OVERLAY, QuizLock.Need.EXACT_ALARM, QuizLock.Need.NOTIFICATION),
            QuizLock.missing(sdk = 33, overlay = false, exactAlarm = false, batteryFree = true, notifications = false, fullScreen = false),
        )
    }

    @Test
    fun `all five are asked for where the platform has all five`() {
        assertEquals(
            listOf(
                QuizLock.Need.OVERLAY, QuizLock.Need.EXACT_ALARM, QuizLock.Need.BATTERY,
                QuizLock.Need.NOTIFICATION, QuizLock.Need.FULL_SCREEN,
            ),
            QuizLock.missing(sdk = 34, overlay = false, exactAlarm = false, batteryFree = false, notifications = false, fullScreen = false),
        )
    }

    @Test
    fun `ready means nothing is missing`() {
        assertTrue(QuizLock.ready(34, overlay = true, exactAlarm = true, batteryFree = true, notifications = true, fullScreen = true))
        assertFalse(QuizLock.ready(34, overlay = true, exactAlarm = true, batteryFree = true, notifications = true, fullScreen = false))
        // The same grant set is enough on a level that cannot ask for the newer switches.
        assertTrue(QuizLock.ready(26, overlay = true, exactAlarm = false, batteryFree = true, notifications = false, fullScreen = false))
    }

    // A scheduled alarm that is still in the future and was set after this boot is healthy: the
    // watchdog must not touch it, or every run would push the quiz forward.
    @Test
    fun `a healthy schedule is left alone`() {
        assertFalse(QuizLock.alarmLost(now = 1_000_000, bootAt = 900_000, nextTriggerAt = 1_100_000, lastScheduleAt = 950_000))
    }

    @Test
    fun `nothing scheduled gets one`() {
        assertTrue(QuizLock.alarmLost(now = 1_000_000, bootAt = 900_000, nextTriggerAt = 0, lastScheduleAt = 950_000))
    }

    @Test
    fun `a due time that never fired is lost`() {
        assertTrue(
            QuizLock.alarmLost(
                now = 1_000_000,
                bootAt = 800_000,
                nextTriggerAt = 1_000_000 - QuizLock.OVERDUE_GRACE_MS,
                lastScheduleAt = 850_000,
            ),
        )
    }

    // A watchdog tick milliseconds after the due time must leave AlarmManager alone, or it pushes
    // nextTriggerAt ahead and makes the real alarm log a huge negative lateMs.
    @Test
    fun `an alarm inside the grace window is not stolen by the watchdog`() {
        assertFalse(QuizLock.alarmLost(now = 1_000_000, bootAt = 800_000, nextTriggerAt = 999_980, lastScheduleAt = 850_000))
        assertFalse(QuizLock.alarmLost(now = 1_000_000, bootAt = 800_000, nextTriggerAt = 950_000, lastScheduleAt = 850_000))
    }

    // Hisense VIDAA & Konka V812: AlarmManager never fires while the TV is awake, so the watchdog
    // must open the overdue quiz instead of pushing the deadline forward forever.
    @Test
    fun `watchdog catches up an overdue alarm from this boot while screen is on`() {
        assertTrue(
            QuizLock.watchdogCatchUp(
                now = 1_000_000,
                bootAt = 500_000,
                nextTriggerAt = 900_000,
                lastScheduleAt = 600_000,
                screenInteractive = true,
                sessionActive = false,
            ),
        )
    }

    @Test
    fun `watchdog does not catch up after a reboot, with screen off, or during an active session`() {
        // Set before this boot -> reboot recovery only, do not ambush boot.
        assertFalse(QuizLock.watchdogCatchUp(1_000_000, bootAt = 700_000, nextTriggerAt = 900_000, lastScheduleAt = 600_000, screenInteractive = true, sessionActive = false))
        // Screen off -> reschedule only, never wake a dark screen.
        assertFalse(QuizLock.watchdogCatchUp(1_000_000, bootAt = 500_000, nextTriggerAt = 900_000, lastScheduleAt = 600_000, screenInteractive = false, sessionActive = false))
        // Already in quiz/rest -> do not bounce an open session.
        assertFalse(QuizLock.watchdogCatchUp(1_000_000, bootAt = 500_000, nextTriggerAt = 900_000, lastScheduleAt = 600_000, screenInteractive = true, sessionActive = true))
        // Inside grace window -> let AlarmManager fire first.
        assertFalse(QuizLock.watchdogCatchUp(1_000_000, bootAt = 500_000, nextTriggerAt = 990_000, lastScheduleAt = 600_000, screenInteractive = true, sessionActive = false))
    }

    @Test
    fun `routine alarm skips relaunch when quiz is already foreground, forced rest still delivers`() {
        assertFalse(QuizLock.shouldRelaunchForAlarm(quizForeground = true, resting = false))
        assertTrue(QuizLock.shouldRelaunchForAlarm(quizForeground = false, resting = false))
        assertTrue(QuizLock.shouldRelaunchForAlarm(quizForeground = true, resting = true))
    }

    @Test
    fun `self-triggered pause window distinguishes internal intents from real escapes`() {
        assertTrue(QuizLock.isExpectedSelfPause(expectedAtMs = 10_000, nowMs = 10_050))
        assertTrue(QuizLock.isExpectedSelfPause(expectedAtMs = 10_000, nowMs = 12_000))
        assertFalse(QuizLock.isExpectedSelfPause(expectedAtMs = 10_000, nowMs = 12_001))
        assertFalse(QuizLock.isExpectedSelfPause(expectedAtMs = 0, nowMs = 10_050))
    }

    // The Huawei vision screen, 2026-11-10: the alarm was set at 21:25 the night before, the set
    // rebooted at 18:18, and no BOOT_COMPLETED arrived. The due time was still in the future, so only
    // "set before this boot" can tell it is gone.
    @Test
    fun `an alarm set before this boot is gone even when its time has not come`() {
        val bootAt = 1_800_000_000_000L
        val setLastNight = bootAt - 21 * 3_600_000L
        assertTrue(
            QuizLock.alarmLost(
                now = bootAt + 28 * 60_000L,
                bootAt = bootAt,
                nextTriggerAt = bootAt + 60 * 60_000L,
                lastScheduleAt = setLastNight,
            ),
        )
    }

    // Never stamped (an install upgraded from an older build): unknown counts as lost, and the
    // re-schedule stamps it so this happens once rather than on every run.
    @Test
    fun `an unknown schedule time counts as lost`() {
        assertTrue(QuizLock.alarmLost(now = 1_000_000, bootAt = 900_000, nextTriggerAt = 1_100_000, lastScheduleAt = 0))
    }

    @Test
    fun `an unanswered quiz owns Home`() {
        assertTrue(QuizLock.sessionActive(quizActive = true, restGuard = false, restUntil = 0, now = 1_000))
        assertFalse(QuizLock.sessionActive(quizActive = false, restGuard = false, restUntil = 0, now = 1_000))
    }

    @Test
    fun `only an unexpired enforced rest owns Home`() {
        assertTrue(QuizLock.sessionActive(quizActive = false, restGuard = true, restUntil = 1_001, now = 1_000))
        assertFalse(QuizLock.sessionActive(quizActive = false, restGuard = true, restUntil = 1_000, now = 1_000))
        assertFalse(QuizLock.sessionActive(quizActive = false, restGuard = false, restUntil = 2_000, now = 1_000))
    }

    @Test
    fun `a parent rest owns Home even when anti-guess rest is off`() {
        assertTrue(QuizLock.sessionActive(quizActive = false, restGuard = false, restUntil = 1_001, now = 1_000, restForced = true))
        assertFalse(QuizLock.sessionActive(quizActive = false, restGuard = false, restUntil = 1_000, now = 1_000, restForced = true))
    }

    @Test
    fun `narration needs a bright foreground page`() {
        assertTrue(QuizLock.shouldNarrate(screenInteractive = true, pageForeground = true))
        assertFalse(QuizLock.shouldNarrate(screenInteractive = false, pageForeground = true))
        assertFalse(QuizLock.shouldNarrate(screenInteractive = true, pageForeground = false))
    }

    // A firmware that delivers one alarm twice sends the second a moment after the first. Acting on
    // it would reschedule, pop the page again and count as a second alarm in the report.
    @Test
    fun `a second delivery within seconds is the same alarm`() {
        assertTrue(QuizLock.duplicateAlarm(lastAlarmAt = 1_000_000, now = 1_000_000))
        assertTrue(QuizLock.duplicateAlarm(lastAlarmAt = 1_000_000, now = 1_002_999))
    }

    @Test
    fun `a delivery a minute later is a new alarm`() {
        assertFalse(QuizLock.duplicateAlarm(lastAlarmAt = 1_000_000, now = 1_003_000))
        assertFalse(QuizLock.duplicateAlarm(lastAlarmAt = 1_000_000, now = 1_060_000))
    }

    @Test
    fun `the first alarm ever is never a duplicate`() {
        assertFalse(QuizLock.duplicateAlarm(lastAlarmAt = 0, now = 1_000_000))
    }

    // The guard is asked for from every page and from background wake-ups. A plain start arms no
    // countdown; a foreground start from the background does, and the TCL firmware never answers it.
    @Test
    fun `a visible page starts the guard without a deadline`() {
        assertEquals(QuizLock.GuardStart.PLAIN, QuizLock.guardStart(visible = true, tcl = true))
        assertEquals(QuizLock.GuardStart.PLAIN, QuizLock.guardStart(visible = true, tcl = false))
    }

    @Test
    fun `the tcl firmware gets no background request at all`() {
        assertEquals(QuizLock.GuardStart.SKIP, QuizLock.guardStart(visible = false, tcl = true))
    }

    @Test
    fun `other firmwares keep the background foreground start`() {
        assertEquals(QuizLock.GuardStart.FOREGROUND, QuizLock.guardStart(visible = false, tcl = false))
    }

    @Test
    fun `forced rest interrupts wrong-answer explanation while anti-guess rest waits for it to finish`() {
        assertFalse(QuizLock.shouldInterruptForRest(resting = false, explaining = false, restForced = false))
        assertTrue(QuizLock.shouldInterruptForRest(resting = true, explaining = false, restForced = false))
        assertFalse(QuizLock.shouldInterruptForRest(resting = true, explaining = true, restForced = false))
        assertTrue(QuizLock.shouldInterruptForRest(resting = true, explaining = true, restForced = true))
    }
}
