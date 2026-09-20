package org.example.project.guidance

/**
 * Outcome of asking an external rescuer (in practice, an LLM) to suggest a
 * target when GuidanceEngine has declared Lost. This is a one-off, lower-
 * confidence suggestion overlaid on top of whatever GuidanceEngine is doing
 * -- it never changes engine state. If the next real screen poll finds a
 * genuine matcher-based match, that takes over exactly as if no rescue had
 * been attempted.
 */
sealed interface LostRescueResult {
    data class Found(val element: ScreenElement, val instruction: String) : LostRescueResult
    data object Unclear : LostRescueResult
    data class Failed(val reason: String) : LostRescueResult
}

/**
 * Platform-specific (needs networking), so only the contract lives here.
 * Implemented in androidApp; shared stays network-free.
 */
interface LostRescuer {
    fun rescue(
        elements: List<ScreenElement>,
        remainingSteps: List<GuidanceStep>,
        onResult: (LostRescueResult) -> Unit,
    )
}

/** Always fails immediately. The default when no real rescuer is wired up (e.g. no API key set). */
object NoOpLostRescuer : LostRescuer {
    override fun rescue(
        elements: List<ScreenElement>,
        remainingSteps: List<GuidanceStep>,
        onResult: (LostRescueResult) -> Unit,
    ) {
        onResult(LostRescueResult.Failed("no rescuer configured"))
    }
}
