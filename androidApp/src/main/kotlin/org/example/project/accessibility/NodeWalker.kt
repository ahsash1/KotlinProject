package org.example.project.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.example.project.guidance.ScreenElement

/** Flattens the accessibility node tree into [ScreenElement]s. Never throws. */
object NodeWalker {

    fun walk(root: AccessibilityNodeInfo?): List<ScreenElement> {
        if (root == null) return emptyList()

        val result = mutableListOf<ScreenElement>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        val rect = Rect()

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            try {
                for (i in 0 until node.childCount) {
                    val child = try {
                        node.getChild(i)
                    } catch (t: Throwable) {
                        null
                    }
                    if (child != null) stack.addLast(child)
                }

                node.getBoundsInScreen(rect)
                val visible = node.isVisibleToUser && rect.width() > 0 && rect.height() > 0
                if (visible) {
                    result += ScreenElement(
                        text = node.text?.toString()?.takeIf { it.isNotBlank() },
                        contentDescription = node.contentDescription?.toString()?.takeIf { it.isNotBlank() },
                        viewId = node.viewIdResourceName,
                        className = node.className?.toString(),
                        left = rect.left,
                        top = rect.top,
                        right = rect.right,
                        bottom = rect.bottom,
                        isClickable = node.isClickable,
                        isVisible = true,
                    )
                }
            } catch (t: IllegalStateException) {
                // Node went stale mid-walk (window changed underneath us). Skip it.
            } catch (t: Throwable) {
                // Never let one bad node take down the whole walk.
            } finally {
                @Suppress("DEPRECATION")
                try {
                    node.recycle()
                } catch (t: Throwable) {
                    // recycle() is a no-op on modern API levels; ignore failures either way.
                }
            }
        }
        return result
    }

    /**
     * Re-walks the CURRENT tree to find a live node matching a previously-captured [ScreenElement]
     * snapshot -- needed because a snapshot carries no live node reference (by design; see
     * ScreenElement's own doc comment), and time may have passed since it was taken (a Help round
     * trip can take several seconds). Exact match on bounds + text + contentDescription only, no
     * fuzzy fallback -- if the screen changed enough that this doesn't match, returning null and
     * letting the caller fail gracefully is safer than guessing.
     *
     * Caller owns the returned node and must recycle it; every other node visited during the
     * search is recycled internally. Returns null (and recycles everything) if nothing matches.
     */
    fun findLiveNode(root: AccessibilityNodeInfo?, target: ScreenElement): AccessibilityNodeInfo? {
        if (root == null) return null

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        val rect = Rect()

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            var isMatch = false
            try {
                node.getBoundsInScreen(rect)
                isMatch = node.isVisibleToUser &&
                    rect.left == target.left && rect.top == target.top &&
                    rect.right == target.right && rect.bottom == target.bottom &&
                    (node.text?.toString().orEmpty()) == target.text.orEmpty() &&
                    (node.contentDescription?.toString().orEmpty()) == target.contentDescription.orEmpty()

                if (!isMatch) {
                    for (i in 0 until node.childCount) {
                        val child = try {
                            node.getChild(i)
                        } catch (t: Throwable) {
                            null
                        }
                        if (child != null) stack.addLast(child)
                    }
                }
            } catch (t: Throwable) {
                // Treat as no match; fall through to recycle below.
            }

            if (isMatch) {
                while (stack.isNotEmpty()) {
                    @Suppress("DEPRECATION")
                    try {
                        stack.removeLast().recycle()
                    } catch (t: Throwable) {
                        // best-effort cleanup
                    }
                }
                return node
            } else {
                @Suppress("DEPRECATION")
                try {
                    node.recycle()
                } catch (t: Throwable) {
                    // recycle() is a no-op on modern API levels; ignore failures either way.
                }
            }
        }
        return null
    }
}
