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

    // ---- growth rating: seven ranks, four stages each, points from correct and wrong answers ----

    /** Points added for a correct answer. */
    const val POINTS_CORRECT = 10

    /** Points subtracted for a wrong answer. */
    const val POINTS_WRONG = -5

    /** Extra points subtracted when the same question is answered wrong again without being solved in between. */
    const val POINTS_REPEAT_WRONG_EXTRA = -8

    /** The score from which the top stage (Diamond 4) starts. */
    const val TOP_SCORE = 40_000

    /** Rank names from the lowest to the highest. */
    val RANKS = arrayOf("塑料", "铁", "铜", "白银", "黄金", "铂金", "钻石")

    /** One color per rank, in the same order as [RANKS]; shown on the badge and the stats page. */
    val RANK_COLORS = intArrayOf(
        0xFFA8B3C1.toInt(), // 塑料
        0xFF7B8CA3.toInt(), // 铁
        0xFFD08A4E.toInt(), // 铜
        0xFFD7DEE8.toInt(), // 白银
        0xFFFFD166.toInt(), // 黄金
        0xFF8FE8D8.toInt(), // 铂金
        0xFF8FD0FF.toInt(), // 钻石
    )

    /** One friendly icon per rank, in the same order as [RANKS]. */
    val RANK_ICONS = arrayOf("🌱", "🪨", "🥉", "🥈", "🥇", "💠", "💎")

    /** Stage count inside every rank. */
    const val STAGES_PER_RANK = 4

    /** Total number of stages: seven ranks times four stages. */
    val TIER_COUNT = RANKS.size * STAGES_PER_RANK

    /**
     * The stage a score belongs to: 0 is Plastic 1 and 27 is Diamond 4. Negative scores stay at the
     * bottom stage; TOP_SCORE and above is the top stage; everything between splits evenly.
     */
    fun tierIndex(score: Int): Int = when {
        score >= TOP_SCORE -> TIER_COUNT - 1
        score <= 0 -> 0
        else -> (score.toLong() * TIER_COUNT / TOP_SCORE).toInt().coerceAtMost(TIER_COUNT - 2)
    }

    /** The lowest score that still belongs to the given stage; useful for progress bars. */
    fun tierFloor(index: Int): Int = when {
        index <= 0 -> 0
        index >= TIER_COUNT - 1 -> TOP_SCORE
        else -> (index.toLong() * TOP_SCORE + TIER_COUNT - 1).div(TIER_COUNT.toLong()).toInt()
    }

    /** The full name of a stage, for example "黄金 2阶". */
    fun tierName(index: Int): String {
        val i = index.coerceIn(0, TIER_COUNT - 1)
        return "${RANKS[i / STAGES_PER_RANK]} ${i % STAGES_PER_RANK + 1}阶"
    }

    /** Points for one answer; a repeated wrong answer carries the extra penalty. */
    fun pointsFor(correct: Boolean, repeatWrong: Boolean): Int = when {
        correct -> POINTS_CORRECT
        repeatWrong -> POINTS_WRONG + POINTS_REPEAT_WRONG_EXTRA
        else -> POINTS_WRONG
    }
}
