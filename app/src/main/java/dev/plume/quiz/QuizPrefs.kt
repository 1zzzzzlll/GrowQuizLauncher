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

    /** Set at power-on while the boot quiz is on; cleared when a question is answered correctly. */
    var bootPending: Boolean
        get() = prefs.getBoolean(KEY_BOOT_PENDING, false)
        set(value) = prefs.edit().putBoolean(KEY_BOOT_PENDING, value).apply()

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
        private const val KEY_BOOT_PENDING = "boot_quiz_pending"
    }
}
