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
    /** True for questions written by parents through the admin console, false for bundled/online banks. */
    val custom: Boolean = false,
    /** True for textbook-aligned questions pulled in the background from the online bank. */
    val online: Boolean = false,
)
