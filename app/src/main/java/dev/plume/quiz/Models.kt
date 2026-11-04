package dev.plume.quiz

/** One immutable multiple-choice question. The answer index is zero based. */
data class Question(
    val id: String,
    val age: Int,
    val category: String,
    val prompt: String,
    val options: List<String>,
    val answerIndex: Int,
    val explanation: String,
)
