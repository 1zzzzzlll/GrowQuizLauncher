package dev.plume.quiz

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * The growth ledger: every graded answer becomes one line in quiz_attempts.jsonl, and the running
 * totals (score, answer counts, per-question wrong memory) live in shared preferences so the quiz
 * page can show the badge without reading the log. A correct answer clears that question's wrong
 * memory: a child who learned the question no longer pays the repeat penalty for it.
 */
class QuizLedger(context: Context) {
    private val stats =
        context.applicationContext.getSharedPreferences(STATS_NAME, Context.MODE_PRIVATE)
    private val logFile = File(context.applicationContext.filesDir, LOG_NAME)

    /** One graded answer, as kept in the log. */
    data class Attempt(
        val timeMs: Long,
        val id: String,
        val age: Int,
        val category: String,
        val correct: Boolean,
        val repeatWrong: Boolean,
        val snap: Boolean,
    )

    /** What a recorded answer changed: the score before and after, plus the badge stage transition. */
    data class Result(
        val score: Int,
        val delta: Int,
        val tierBefore: Int,
        val tierAfter: Int,
    )

    /** The current points total, negative included. */
    fun score(): Int = stats.getInt(KEY_SCORE, 0)

    /** Lifetime answer counts: correct, wrong, and wrong-on-a-known-question. */
    fun totals(): Triple<Int, Int, Int> = Triple(
        stats.getInt(KEY_CORRECT, 0),
        stats.getInt(KEY_WRONG, 0),
        stats.getInt(KEY_REPEAT, 0),
    )

    /** How many times this question was answered wrong without being solved in between. */
    fun wrongMemory(id: String): Int = stats.getInt(KEY_WRONG_MEMORY + id, 0)

    /**
     * Records one graded answer, updates the score and the totals, appends the log line and returns
     * the badge transition so the UI can celebrate a stage-up.
     */
    @Synchronized
    fun record(question: Question, correct: Boolean, snap: Boolean, nowMs: Long = System.currentTimeMillis()): Result {
        val memoryKey = KEY_WRONG_MEMORY + question.id
        val previousWrongs = stats.getInt(memoryKey, 0)
        val repeatWrong = !correct && previousWrongs > 0
        val delta = QuizRules.pointsFor(correct, repeatWrong)
        val before = score()
        val after = before + delta
        val editor = stats.edit()
            .putInt(KEY_SCORE, after)
            .putInt(KEY_CORRECT, stats.getInt(KEY_CORRECT, 0) + if (correct) 1 else 0)
            .putInt(KEY_WRONG, stats.getInt(KEY_WRONG, 0) + if (correct) 0 else 1)
            .putInt(KEY_REPEAT, stats.getInt(KEY_REPEAT, 0) + if (repeatWrong) 1 else 0)
        if (correct) {
            editor.remove(memoryKey) // solved: the repeat penalty for this question starts over
        } else {
            editor.putInt(memoryKey, previousWrongs + 1)
        }
        editor.apply()
        appendLog(Attempt(nowMs, question.id, question.age, question.category, correct, repeatWrong, snap))
        return Result(after, delta, QuizRules.tierIndex(before), QuizRules.tierIndex(after))
    }

    /** Every logged attempt, oldest first; a damaged file is skipped line by line, never fatal. */
    fun attempts(): List<Attempt> {
        if (!logFile.exists()) return emptyList()
        val result = ArrayList<Attempt>()
        try {
            logFile.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEachLine
                try {
                    val obj = JSONObject(trimmed)
                    result += Attempt(
                        timeMs = obj.getLong("t"),
                        id = obj.optString("id"),
                        age = obj.optInt("age"),
                        category = obj.optString("cat"),
                        correct = obj.optInt("ok") == 1,
                        repeatWrong = obj.optInt("rw") == 1,
                        snap = obj.optInt("sn") == 1,
                    )
                } catch (_: Exception) {
                    // one broken line (a crash mid-write) must not hide the rest of the history
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    /** Appends one line and, when the log grows past the cap, rewrites it with the newest lines kept. */
    private fun appendLog(attempt: Attempt) {
        try {
            logFile.appendText(attempt.toJson() + "\n", Charsets.UTF_8)
            if (logFile.length() > TRIM_BYTES) trimLog()
        } catch (_: Exception) {
            // the ledger must never break answering: losing one log line is acceptable
        }
    }

    private fun trimLog() {
        try {
            val lines = logFile.readLines(Charsets.UTF_8)
            if (lines.size <= KEEP_LINES) return
            val tmp = File(logFile.parentFile, "$LOG_NAME.tmp")
            tmp.writeText(lines.takeLast(KEEP_LINES).joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            if (!tmp.renameTo(logFile)) {
                logFile.writeText(lines.takeLast(KEEP_LINES).joinToString("\n", postfix = "\n"), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun Attempt.toJson(): String = JSONObject()
        .put("t", timeMs)
        .put("id", id)
        .put("age", age)
        .put("cat", category)
        .put("ok", if (correct) 1 else 0)
        .put("rw", if (repeatWrong) 1 else 0)
        .put("sn", if (snap) 1 else 0)
        .toString()

    companion object {
        private const val STATS_NAME = "grow_quiz_stats" // the same file QuestionBank keeps correct counts in
        private const val LOG_NAME = "quiz_attempts.jsonl"
        private const val KEY_SCORE = "ledger_score"
        private const val KEY_CORRECT = "ledger_correct"
        private const val KEY_WRONG = "ledger_wrong"
        private const val KEY_REPEAT = "ledger_repeat"
        private const val KEY_WRONG_MEMORY = "wrong_"
        private const val TRIM_BYTES = 3_000_000L // ~24k lines: trim before the file gets big
        private const val KEEP_LINES = 20_000
    }
}
