package org.example.project.guidance

/**
 * A platform-free snapshot of one node on screen. Android builds this from
 * AccessibilityNodeInfo; any other platform would build it from whatever its
 * own accessibility/automation API exposes.
 */
data class ScreenElement(
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val className: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val isClickable: Boolean,
    val isVisible: Boolean,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Long get() = width.toLong() * height.toLong()

    /** True if [other]'s bounds sit entirely inside this element's bounds. */
    fun contains(other: ScreenElement): Boolean =
        left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
}

/**
 * Normalises text for matching: trims, collapses internal whitespace, strips
 * punctuation, and (by default) lowercases. Applied identically to both sides
 * of every comparison so matches survive minor OEM/locale copy differences.
 */
fun normalizeForMatch(value: String, ignoreCase: Boolean = true): String {
    val strippedPunctuation = value.filter { it.isLetterOrDigit() || it.isWhitespace() }
    val collapsed = strippedPunctuation.trim().replace(Regex("\\s+"), " ")
    return if (ignoreCase) collapsed.lowercase() else collapsed
}
