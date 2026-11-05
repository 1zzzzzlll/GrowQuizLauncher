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
}
