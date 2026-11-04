package dev.plume.quiz

/** Pure rules shared by the UI and scheduler, kept independent of Android for regression tests. */
object QuizRules {
    fun isCorrect(question: Question, selectedIndex: Int): Boolean = selectedIndex == question.answerIndex

    fun triggerAt(nowMillis: Long, intervalMinutes: Int): Long =
        nowMillis + intervalMinutes.coerceIn(1, 1440) * 60_000L
}
