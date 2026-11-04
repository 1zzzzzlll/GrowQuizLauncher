package dev.plume.quiz

import android.content.Context

/** Small local settings store; no account, network or cloud dependency. */
class QuizPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var age: Int
        get() = prefs.getInt(KEY_AGE, 7).coerceIn(5, 10)
        set(value) = prefs.edit().putInt(KEY_AGE, value.coerceIn(5, 10)).apply()

    var intervalMinutes: Int
        get() = prefs.getInt(KEY_INTERVAL, 30).coerceIn(1, 1440)
        set(value) = prefs.edit().putInt(KEY_INTERVAL, value.coerceIn(1, 1440)).apply()

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var nextTriggerAt: Long
        get() = prefs.getLong(KEY_NEXT, 0L)
        set(value) = prefs.edit().putLong(KEY_NEXT, value).apply()

    fun save(age: Int, interval: Int, enabled: Boolean) {
        prefs.edit()
            .putInt(KEY_AGE, age.coerceIn(5, 10))
            .putInt(KEY_INTERVAL, interval.coerceIn(1, 1440))
            .putBoolean(KEY_ENABLED, enabled)
            .commit()
    }

    companion object {
        const val NAME = "grow_quiz"
        private const val KEY_AGE = "age"
        private const val KEY_INTERVAL = "interval_minutes"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_NEXT = "next_trigger_at"
    }
}
