package org.example.project.guidance

/**
 * Kill switches for the additive, higher-risk layers. Everything else in
 * the app works with all of these off. Flip one off here (no other file
 * needs to change) if it proves unreliable in testing.
 */
object FeatureFlags {
    /** LLM fallback attempted once per Lost episode. Never overrides a working matcher-based step. */
    var lostRescueEnabled: Boolean = true

    /** Mic button in the launcher for picking a task by voice instead of tapping a card. */
    var voiceTaskSelectionEnabled: Boolean = true
}
