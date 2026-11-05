package dev.plume.quiz

/** Pure rules shared by the UI and scheduler, kept independent of Android for regression tests. */
object QuizRules {
    /** Default seconds of forced thinking time before the buttons respond; the web console can change it. */
    const val DEFAULT_THINK_SECONDS = 5

    /** From this many wrong answers in a row, a rest is enforced instead of another question. */
    const val REST_STREAK_START = 3

    /** Minutes of enforced rest per wrong answer beyond the threshold. */
    const val REST_MINUTES_STEP = 10

    fun isCorrect(question: Question, selectedIndex: Int): Boolean = selectedIndex == question.answerIndex

    fun triggerAt(nowMillis: Long, intervalMinutes: Int): Long =
        nowMillis + intervalMinutes.coerceIn(1, 1440) * 60_000L

    /**
     * Minutes of enforced rest for the given wrong-answer streak:
     * 3 wrong in a row -> 10 minutes, 4 -> 20, 5 -> 30, and so on.
     */
    fun restMinutes(wrongStreak: Int): Int =
        if (wrongStreak < REST_STREAK_START) 0 else (wrongStreak - REST_STREAK_START + 1) * REST_MINUTES_STEP

    /** Wall-clock time when the rest ends for the given streak; 0 when no rest is owed. */
    fun restUntil(nowMillis: Long, wrongStreak: Int): Long =
        nowMillis + restMinutes(wrongStreak) * 60_000L

    /** An answer given this soon after the think window opened counts as a snap (blind) answer. */
    const val SNAP_ANSWER_MS = 5_000L

    /** Two snap wrong answers in a row followed by a snap correct answer looks like lucky guessing. */
    const val GUESS_SNAP_WRONGS = 2

    fun isSnapAnswer(nowMillis: Long, readyAtMillis: Long): Boolean =
        readyAtMillis > 0 && nowMillis - readyAtMillis < SNAP_ANSWER_MS

    /** True when a fast correct answer deserves one extra verification question. */
    fun needsVerification(snapWrongs: Int, correctWasSnap: Boolean): Boolean =
        correctWasSnap && snapWrongs >= GUESS_SNAP_WRONGS
}
