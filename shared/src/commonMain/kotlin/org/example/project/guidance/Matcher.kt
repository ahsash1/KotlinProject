package org.example.project.guidance

/**
 * A signal a [GuidanceStep] can look for on screen. A step carries several of
 * these so it isn't tied to one brittle string; ANY matcher hitting counts as
 * the step being found.
 */
sealed interface Matcher {
    data class TextEquals(val value: String, val ignoreCase: Boolean = true) : Matcher
    data class TextContains(val value: String, val ignoreCase: Boolean = true) : Matcher
    data class ContentDescContains(val value: String, val ignoreCase: Boolean = true) : Matcher
    /** Most stable when the target app exposes one; exact match on the resource id. */
    data class ViewIdEquals(val value: String) : Matcher
}

/** Whether [element] satisfies this matcher, after normalising both sides. */
fun Matcher.matches(element: ScreenElement): Boolean = when (this) {
    is Matcher.TextEquals -> {
        val needle = normalizeForMatch(value, ignoreCase)
        needle.isNotEmpty() && element.text?.let { normalizeForMatch(it, ignoreCase) == needle } == true
    }
    is Matcher.TextContains -> {
        val needle = normalizeForMatch(value, ignoreCase)
        needle.isNotEmpty() && element.text?.let { normalizeForMatch(it, ignoreCase).contains(needle) } == true
    }
    is Matcher.ContentDescContains -> {
        val needle = normalizeForMatch(value, ignoreCase)
        needle.isNotEmpty() &&
            element.contentDescription?.let { normalizeForMatch(it, ignoreCase).contains(needle) } == true
    }
    is Matcher.ViewIdEquals -> {
        val needle = normalizeForMatch(value)
        needle.isNotEmpty() && element.viewId?.let { normalizeForMatch(it) == needle } == true
    }
}

/**
 * Finds the element this step should point at, or null if nothing on screen
 * matches. Matchers are tried in order; the first matcher with any hit wins,
 * and among its hits we prefer a clickable element, then the largest, then
 * the topmost.
 */
fun GuidanceStep.findElement(elements: List<ScreenElement>): ScreenElement? {
    for (matcher in matchers) {
        val hits = elements.filter { it.isVisible && matcher.matches(it) }
        if (hits.isNotEmpty()) {
            return hits.sortedWith(
                compareByDescending<ScreenElement> { it.isClickable }
                    .thenByDescending { it.area }
                    .thenBy { it.top }
            ).first()
        }
    }
    return null
}

/**
 * If [element] isn't itself clickable, walks up to the nearest clickable
 * ancestor. ScreenElement carries no parent pointer, so "ancestor" is
 * approximated geometrically: the smallest visible clickable element whose
 * bounds fully contain [element]'s bounds.
 */
fun resolveClickableTarget(element: ScreenElement, elements: List<ScreenElement>): ScreenElement {
    if (element.isClickable) return element
    return elements
        .asSequence()
        .filter { it != element && it.isVisible && it.isClickable && it.contains(element) }
        .minByOrNull { it.area }
        ?: element
}
