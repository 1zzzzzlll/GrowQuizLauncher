package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The rule that decides whether a finished quiz hands a player back. */
class QuizReturnTest {

    private val hour = 3_600_000L

    @Test
    fun `the app in front when the quiz opened is handed back`() {
        assertEquals(
            "com.ktcp.video",
            QuizReturn.pick("com.ktcp.video", appAt = 1_000_000, desktopAt = 900_000, now = 1_060_000, maxAgeMs = 6 * hour),
        )
    }

    @Test
    fun `a quiz that opened over the desktop hands nothing back`() {
        assertNull(QuizReturn.pick("com.ktcp.video", appAt = 900_000, desktopAt = 1_000_000, now = 1_060_000, maxAgeMs = 6 * hour))
    }

    @Test
    fun `the same second counts as the desktop`() {
        assertNull(QuizReturn.pick("com.ktcp.video", appAt = 1_000_000, desktopAt = 1_000_000, now = 1_060_000, maxAgeMs = 6 * hour))
    }

    @Test
    fun `a player left behind hours ago is not the interrupted one`() {
        assertNull(QuizReturn.pick("com.ktcp.video", appAt = 1_000_000, desktopAt = 0, now = 1_000_000 + 7 * hour, maxAgeMs = 6 * hour))
    }

    @Test
    fun `nothing recorded means nothing to hand back`() {
        assertNull(QuizReturn.pick(null, appAt = 0, desktopAt = 0, now = 1_000_000, maxAgeMs = 6 * hour))
        assertNull(QuizReturn.pick("", appAt = 0, desktopAt = 0, now = 1_000_000, maxAgeMs = 6 * hour))
    }

    // A clock that ran backwards (a power cut, a manual change) must not turn a record into a target.
    @Test
    fun `a record from the future is ignored`() {
        assertNull(QuizReturn.pick("com.ktcp.video", appAt = 2_000_000, desktopAt = 0, now = 1_000_000, maxAgeMs = 6 * hour))
    }
}
