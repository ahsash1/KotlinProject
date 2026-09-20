package org.example.project.guidance

/**
 * Matches a spoken transcript against a task's title/examples. Deterministic,
 * no network/LLM call -- this only decides WHICH task to start, so a wrong
 * guess is cheap to notice and retry, unlike a wrong guess mid-flow.
 */
object VoiceTaskMatcher {

    // Excluded from the word-overlap score only (not from the substring check below) so two
    // unrelated tasks don't "match" on a shared filler word like "get" or "a".
    private val STOPWORDS = setOf(
        "a", "an", "the", "to", "i", "me", "my", "please", "can", "you",
        "want", "need", "get", "go", "do", "help", "with", "for", "on", "in",
    )

    /** Null means no confident match; caller should ask the user to choose instead. */
    fun match(transcript: String, tasks: List<GuidanceTask>): GuidanceTask? {
        val normalizedTranscript = normalizeForMatch(transcript)
        if (normalizedTranscript.isBlank()) return null
        val transcriptWords = normalizedTranscript.split(' ').filter { it.isNotBlank() && it !in STOPWORDS }.toSet()
        if (transcriptWords.isEmpty()) return null

        var best: GuidanceTask? = null
        var bestScore = 0

        for (task in tasks) {
            for (candidate in listOf(task.title) + task.examples) {
                val normalizedCandidate = normalizeForMatch(candidate)
                if (normalizedCandidate.isBlank()) continue

                // Either phrase containing the other is a strong, unambiguous signal.
                if (normalizedTranscript.contains(normalizedCandidate) ||
                    normalizedCandidate.contains(normalizedTranscript)
                ) {
                    return task
                }

                val candidateWords = normalizedCandidate.split(' ')
                    .filter { it.isNotBlank() && it !in STOPWORDS }
                    .toSet()
                val overlap = transcriptWords.intersect(candidateWords).size
                if (overlap > bestScore) {
                    bestScore = overlap
                    best = task
                }
            }
        }

        // Require at least one real shared word so unrelated speech doesn't match by accident.
        return if (bestScore >= 1) best else null
    }
}
