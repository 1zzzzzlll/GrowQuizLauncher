package dev.plume

import dev.plume.quiz.Question
import dev.plume.quiz.QuizRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuizRulesTest {
    private val question = Question("a5-0001", 5, "数学", "1 + 1 = ?", listOf("1", "2", "3", "4"), 1, "1和1合起来是2。")

    @Test
    fun onlyTheCorrectIndexPasses() {
        assertTrue(QuizRules.isCorrect(question, 1))
        assertFalse(QuizRules.isCorrect(question, 0))
        assertFalse(QuizRules.isCorrect(question, -1))
    }

    @Test
    fun intervalUsesMinutesWithoutOverflowingConfiguration() {
        assertEquals(60_000L, QuizRules.triggerAt(0L, 1))
        assertEquals(1_800_000L, QuizRules.triggerAt(0L, 30))
        assertEquals(60_000L, QuizRules.triggerAt(0L, 0))
        assertEquals(86_400_000L, QuizRules.triggerAt(0L, 5000))
    }

    @Test
    fun restStartsAtThreeWrongAnswersAndGrowsByTenMinutesEachTime() {
        assertEquals(0, QuizRules.restMinutes(0))
        assertEquals(0, QuizRules.restMinutes(1))
        assertEquals(0, QuizRules.restMinutes(2))
        assertEquals(10, QuizRules.restMinutes(3))
        assertEquals(20, QuizRules.restMinutes(4))
        assertEquals(30, QuizRules.restMinutes(5))
        assertEquals(80, QuizRules.restMinutes(10))
    }

    @Test
    fun restUntilAddsWholeMinutesFromNow() {
        assertEquals(1_000L + 10 * 60_000L, QuizRules.restUntil(1_000L, 3))
        assertEquals(1_000L, QuizRules.restUntil(1_000L, 2))
    }

    @Test
    fun snapAnswersOnlyCountShortlyAfterTheThinkWindowOpens() {
        val readyAt = 100_000L
        assertTrue(QuizRules.isSnapAnswer(102_000L, readyAt))   // 2 s after the buttons wake: a snap
        assertFalse(QuizRules.isSnapAnswer(106_000L, readyAt))  // 6 s later: the child thought
        assertFalse(QuizRules.isSnapAnswer(140_000L, readyAt))
        assertFalse(QuizRules.isSnapAnswer(50_000L, 0L))        // no window recorded: never a snap
    }

    @Test
    fun verificationOnlyFollowsASnapCorrectAfterTwoSnapWrongs() {
        assertFalse(QuizRules.needsVerification(0, correctWasSnap = true))
        assertFalse(QuizRules.needsVerification(1, correctWasSnap = true))
        assertTrue(QuizRules.needsVerification(2, correctWasSnap = true))
        assertTrue(QuizRules.needsVerification(4, correctWasSnap = true))
        assertFalse(QuizRules.needsVerification(4, correctWasSnap = false))
    }

    @Test
    fun pointsAddForCorrectAnswersAndSubtractForWrongOnes() {
        assertEquals(10, QuizRules.pointsFor(correct = true, repeatWrong = false))
        assertEquals(10, QuizRules.pointsFor(correct = true, repeatWrong = true)) // solving always pays the same
        assertEquals(-5, QuizRules.pointsFor(correct = false, repeatWrong = false))
        assertEquals(-13, QuizRules.pointsFor(correct = false, repeatWrong = true)) // repeated wrong: extra penalty
    }

    @Test
    fun twentyEightStagesSpanTheRanks() {
        assertEquals(7, QuizRules.RANKS.size)
        assertEquals(28, QuizRules.TIER_COUNT)
        assertEquals("塑料 1阶", QuizRules.tierName(0))
        assertEquals("塑料 4阶", QuizRules.tierName(3))
        assertEquals("铁 1阶", QuizRules.tierName(4))
        assertEquals("白银 4阶", QuizRules.tierName(15))
        assertEquals("黄金 2阶", QuizRules.tierName(17))
        assertEquals("铂金 1阶", QuizRules.tierName(20))
        assertEquals("钻石 1阶", QuizRules.tierName(24))
        assertEquals("钻石 4阶", QuizRules.tierName(27))
        assertEquals("塑料 1阶", QuizRules.tierName(-3))  // out of range stays inside
        assertEquals("钻石 4阶", QuizRules.tierName(99))
    }

    @Test
    fun negativeScoresSitAtTheBottomAndTopScoreCapsTheTop() {
        assertEquals(0, QuizRules.tierIndex(-5_000))
        assertEquals(0, QuizRules.tierIndex(0))
        assertEquals(27, QuizRules.tierIndex(40_000))
        assertEquals(27, QuizRules.tierIndex(123_456))
        assertEquals(26, QuizRules.tierIndex(39_999)) // one point below the top stays Diamond 3
    }

    @Test
    fun stagesSplitTheRangeEvenlyAndFloorsMatchTheirStage() {
        // Every stage floor maps back to its own stage, and one point below maps to the stage below.
        for (index in 0 until QuizRules.TIER_COUNT) {
            val floor = QuizRules.tierFloor(index)
            assertEquals(index, QuizRules.tierIndex(floor))
            if (index > 0 && floor > 1) {
                assertEquals(index - 1, QuizRules.tierIndex(floor - 1))
            }
        }
        // A first correct answer from zero lands in stage 0; the stage widths are ~1429 points.
        assertEquals(0, QuizRules.tierIndex(10))
        assertEquals(1, QuizRules.tierIndex(QuizRules.tierFloor(1)))
        assertEquals(1_429, QuizRules.tierFloor(1))
        assertEquals(28_572, QuizRules.tierFloor(20))
    }

    @Test
    fun fourThousandNetCorrectAnswersReachDiamondFour() {
        // 4000 correct answers at +10 with no wrong ones reach the top score exactly.
        var score = 0
        repeat(4_000) { score += QuizRules.pointsFor(correct = true, repeatWrong = false) }
        assertEquals(40_000, score)
        assertEquals(27, QuizRules.tierIndex(score))
    }
}
