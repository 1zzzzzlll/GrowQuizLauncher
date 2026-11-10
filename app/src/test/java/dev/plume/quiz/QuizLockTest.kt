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
}
