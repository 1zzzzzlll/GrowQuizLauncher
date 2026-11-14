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
        assertTrue(QuizLock.alarmLost(now = 1_000_000, bootAt = 900_000, nextTriggerAt = 990_000, lastScheduleAt = 950_000))
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
}
