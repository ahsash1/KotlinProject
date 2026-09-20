package org.example.project.accessibility

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * ============================================================================================
 * THE SOLE SANCTIONED EXCEPTION to this app's hard rule of never calling performAction.
 *
 * Every other guidance path in this app -- GuidanceEngine, the normal overlay, the automatic
 * Lost-rescue -- only ever POINTS. This object is the only place in the codebase that actually
 * acts on a node, and it is reachable from exactly one path: GuidanceAccessibilityService's Help
 * flow, and only when the user explicitly asked the Help AI to perform the action for them (not
 * the default "tell me what to do" behavior). Anything flagged consequential -- either by the
 * LLM itself or by a task's own finalActionMatchers (a human-verified backstop; see Tasks.kt) --
 * is gated behind a spoken "say yes to confirm" round trip before this is ever called. See
 * GuidanceAccessibilityService.handlePerformableAction for that gate.
 * ============================================================================================
 */
object LiveActionPerformer {

    /** Taps [node], climbing to the nearest clickable ancestor first if it isn't clickable itself. */
    fun click(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return performClickSafely(node)
        }

        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < MAX_ANCESTOR_DEPTH) {
            if (current.isClickable) {
                val result = performClickSafely(current)
                recycleQuietly(current)
                return result
            }
            val next = current.parent
            recycleQuietly(current)
            current = next
            depth++
        }
        recycleQuietly(current)
        return false
    }

    /** Sets [text] into [node] via ACTION_SET_TEXT; only works on genuinely editable fields. */
    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (t: Throwable) {
            false
        }
    }

    private fun performClickSafely(node: AccessibilityNodeInfo): Boolean = try {
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    } catch (t: Throwable) {
        false
    }

    private fun recycleQuietly(node: AccessibilityNodeInfo?) {
        @Suppress("DEPRECATION")
        try {
            node?.recycle()
        } catch (t: Throwable) {
            // recycle() is a no-op on modern API levels; ignore failures either way.
        }
    }

    private const val MAX_ANCESTOR_DEPTH = 12
}
