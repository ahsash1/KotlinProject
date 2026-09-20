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
}
