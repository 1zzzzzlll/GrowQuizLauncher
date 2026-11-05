package dev.plume.quiz

import android.content.Context

/** Small local settings store; no account, network or cloud dependency. */
class QuizPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var age: Int
        get() = prefs.getInt(KEY_AGE, 7).coerceIn(5, 14)
        set(value) = prefs.edit().putInt(KEY_AGE, value.coerceIn(5, 14)).apply()

    var intervalMinutes: Int
        get() = prefs.getInt(KEY_INTERVAL, 30).coerceIn(1, 1440)
        set(value) = prefs.edit().putInt(KEY_INTERVAL, value.coerceIn(1, 1440)).apply()

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Parents can turn the question read-aloud off from the web console (e.g. for a silent evening). */
    var voice: Boolean
        get() = prefs.getBoolean(KEY_VOICE, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE, value).apply()

    var nextTriggerAt: Long
        get() = prefs.getLong(KEY_NEXT, 0L)
        set(value) = prefs.edit().putLong(KEY_NEXT, value).apply()

    /** Extra dimension picked by the parents: only these categories are asked. Empty means every category. */
    var categories: Set<String>
        get() = prefs.getStringSet(KEY_CATEGORIES, emptySet())!!.toSet()
        set(value) = prefs.edit().putStringSet(KEY_CATEGORIES, value).apply()

    /** One question must be answered before the TV becomes usable after each power-on. On by default. */
    var bootQuiz: Boolean
        get() = prefs.getBoolean(KEY_BOOT_QUIZ, true)
        set(value) = prefs.edit().putBoolean(KEY_BOOT_QUIZ, value).apply()

    /** Parents can switch the enforced rest after repeated wrong answers off from the web console. */
    var restGuard: Boolean
        get() = prefs.getBoolean(KEY_REST_GUARD, true)
        set(value) = prefs.edit().putBoolean(KEY_REST_GUARD, value).apply()

    /** Seconds of forced thinking time before the answer buttons respond; 0 turns the gate off. */
    var thinkSeconds: Int
        get() = prefs.getInt(KEY_THINK_SECONDS, QuizRules.DEFAULT_THINK_SECONDS).coerceIn(0, 60)
        set(value) = prefs.edit().putInt(KEY_THINK_SECONDS, value.coerceIn(0, 60)).apply()

    /** Consecutive wrong answers in the current session. Resets on any correct answer. Survives restarts. */
    var wrongStreak: Int
        get() = prefs.getInt(KEY_WRONG_STREAK, 0)
        set(value) = prefs.edit().putInt(KEY_WRONG_STREAK, value.coerceAtLeast(0)).apply()

    /** Epoch time until which the child must rest after too many wrong answers in a row. */
    var restUntil: Long
        get() = prefs.getLong(KEY_REST_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_REST_UNTIL, value).apply()

    /** Consecutive snap (too-fast) wrong answers, used to catch blind guessing. */
    var snapWrongs: Int
        get() = prefs.getInt(KEY_SNAP_WRONGS, 0)
        set(value) = prefs.edit().putInt(KEY_SNAP_WRONGS, value.coerceAtLeast(0)).apply()

    /** A verification question is owed: the last fast correct answer looked like a lucky guess. */
    var verifyPending: Boolean
        get() = prefs.getBoolean(KEY_VERIFY_PENDING, false)
        set(value) = prefs.edit().putBoolean(KEY_VERIFY_PENDING, value).apply()

    /** Parents released the rest early from the web console: wipe every anti-guess counter. */
    fun clearRest() {
        prefs.edit()
            .putLong(KEY_REST_UNTIL, 0L)
            .putInt(KEY_WRONG_STREAK, 0)
            .putInt(KEY_SNAP_WRONGS, 0)
            .putBoolean(KEY_VERIFY_PENDING, false)
            .commit()
    }

    fun save(age: Int, interval: Int, enabled: Boolean, categories: Set<String> = this.categories, voice: Boolean = this.voice) {
        prefs.edit()
            .putInt(KEY_AGE, age.coerceIn(5, 14))
            .putInt(KEY_INTERVAL, interval.coerceIn(1, 1440))
            .putBoolean(KEY_ENABLED, enabled)
            .putStringSet(KEY_CATEGORIES, categories)
            .putBoolean(KEY_VOICE, voice)
            .commit()
    }

    companion object {
        const val NAME = "grow_quiz"
        private const val KEY_AGE = "age"
        private const val KEY_INTERVAL = "interval_minutes"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_NEXT = "next_trigger_at"
        private const val KEY_CATEGORIES = "categories"
        private const val KEY_VOICE = "voice_enabled"
        private const val KEY_BOOT_QUIZ = "boot_quiz_enabled"
        private const val KEY_REST_GUARD = "rest_guard_enabled"
        private const val KEY_THINK_SECONDS = "think_seconds"
        private const val KEY_WRONG_STREAK = "wrong_streak"
        private const val KEY_REST_UNTIL = "rest_until"
        private const val KEY_SNAP_WRONGS = "snap_wrongs"
        private const val KEY_VERIFY_PENDING = "verify_pending"
    }
}
