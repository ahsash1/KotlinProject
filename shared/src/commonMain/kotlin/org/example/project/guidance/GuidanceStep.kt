package org.example.project.guidance

data class GuidanceStep(
    /** Spoken and shown to the user. */
    val instruction: String,
    /** ANY of these matching is enough to consider the step found. */
    val matchers: List<Matcher>,
    /** Skipped, without ever showing Stuck, if its element never appears. Varies by device. */
    val optional: Boolean = false,
    /** Spoken if the step's element hasn't been found for a while. */
    val scrollHint: String? = null,
)

data class GuidanceTask(
    val id: String,
    val title: String,
    /** The app this task guides the user through, e.g. "com.android.settings". */
    val targetPackage: String,
    val steps: List<GuidanceStep>,
    /** Phrases a spoken request might use for this task, e.g. "get a ride", "book a car". */
    val examples: List<String> = emptyList(),
    /**
     * Elements whose action is known, by whoever wrote this task, to be irreversible or to have
     * a real-world effect for THIS app -- e.g. Uber's actual ride-request button. Used as a
     * human-verified backstop (not just the Help AI's own judgment) before the Help flow is ever
     * allowed to act on the user's behalf: matching one of these always forces a spoken
     * confirmation first, no matter what the AI itself thinks. Empty by default -- most steps
     * matchers just describe where to point, not what's dangerous to press.
     */
    val finalActionMatchers: List<Matcher> = emptyList(),
)
