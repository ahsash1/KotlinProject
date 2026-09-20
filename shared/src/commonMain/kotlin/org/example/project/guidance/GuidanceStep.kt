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
)
