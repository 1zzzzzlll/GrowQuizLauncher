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
}
