package dev.plume.quiz

import android.content.Context
import org.json.JSONObject
import kotlin.random.Random

/** Loads the bundled bank once and deals each age as a shuffled, non-repeating deck. */
class QuestionBank(private val context: Context) {
    private val byAge: Map<Int, List<Question>> by lazy { load() }

    fun count(age: Int): Int = byAge[age]?.size ?: 0

    @Synchronized
    fun next(age: Int, previousId: String? = null): Question {
        val all = byAge[age.coerceIn(5, 10)].orEmpty()
        require(all.isNotEmpty()) { "No questions for age $age" }
        val prefs = context.getSharedPreferences(QuizPrefs.NAME, Context.MODE_PRIVATE)
        val key = "asked_$age"
        val asked = prefs.getStringSet(key, emptySet())!!.toMutableSet()
        var candidates = all.filter { it.id !in asked }
        if (candidates.isEmpty()) {
            asked.clear()
            candidates = all
        }
        if (candidates.size > 1 && previousId != null) {
            candidates = candidates.filter { it.id != previousId }
        }
        val selected = candidates[Random.Default.nextInt(candidates.size)]
        asked += selected.id
        prefs.edit().putStringSet(key, asked).apply()
        return selected
    }

    private fun load(): Map<Int, List<Question>> {
        val text = context.assets.open("questions.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val array = JSONObject(text).getJSONArray("questions")
        val result = mutableMapOf<Int, MutableList<Question>>()
        val ids = HashSet<String>(array.length())
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val id = item.getString("id")
            check(ids.add(id)) { "Duplicate question id: $id" }
            val optionsJson = item.getJSONArray("options")
            val options = List(optionsJson.length()) { optionsJson.getString(it) }
            check(options.size == 4 && options.toSet().size == 4) { "Invalid options: $id" }
            val question = Question(
                id = id,
                age = item.getInt("age"),
                category = item.getString("category"),
                prompt = item.getString("question"),
                options = options,
                answerIndex = item.getInt("answerIndex"),
                explanation = item.getString("explanation"),
            )
            check(question.answerIndex in options.indices)
            result.getOrPut(question.age) { mutableListOf() } += question
        }
        for (age in 5..10) check((result[age]?.size ?: 0) >= 300) { "Too few questions for age $age" }
        return result
    }
}
