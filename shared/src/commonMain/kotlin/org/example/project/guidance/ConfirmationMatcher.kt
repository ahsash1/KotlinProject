package org.example.project.guidance

/**
 * Deterministic yes/no reading of a spoken confirmation reply -- no network/LLM call, used to
 * gate a consequential Help-performed action. Anything that isn't clearly affirmative is treated
 * as "no": the safe default when unsure is to NOT act.
 */
object ConfirmationMatcher {
    private val AFFIRMATIVE_WORDS = setOf("yes", "yeah", "yep", "yup", "confirm", "confirmed", "sure", "ok", "okay", "correct")
    private val AFFIRMATIVE_PHRASES = listOf("go ahead", "do it", "please do")

    fun isAffirmative(text: String): Boolean {
        val normalized = normalizeForMatch(text)
        if (normalized.isBlank()) return false
        val words = normalized.split(' ').filter { it.isNotBlank() }
        if (words.any { it in AFFIRMATIVE_WORDS }) return true
        return AFFIRMATIVE_PHRASES.any { normalized.contains(it) }
    }
}
