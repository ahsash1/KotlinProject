package org.example.project.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import org.example.project.guidance.ScreenElement

/**
 * Attaches/detaches a single full-screen [OverlayView] via
 * TYPE_ACCESSIBILITY_OVERLAY (no draw-over-other-apps permission needed).
 * Guards against double-attach/detach so a stray call is always a no-op
 * rather than a crash.
 */
class OverlayController(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val view = OverlayView(service)
    private val helpButton = HelpButtonOverlay(service)
    private var attached = false

    /** Set once by the caller; the Help button forwards its taps here. */
    var onHelpTapped: (() -> Unit)? = null

    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    fun showPointing(element: ScreenElement, message: String) {
        ensureAttached()
        view.mode = OverlayView.Mode.POINTING
        view.targetRect = Rect(element.left, element.top, element.right, element.bottom)
        view.message = message
    }

    /** Lower-confidence LLM guess, visually distinct (purple) from a verified matcher-based [showPointing]. */
    fun showAiSuggestion(element: ScreenElement, message: String) {
        ensureAttached()
        view.mode = OverlayView.Mode.AI_SUGGESTION
        view.targetRect = Rect(element.left, element.top, element.right, element.bottom)
        view.message = message
    }

    /** Same lower-confidence styling, but nothing specific on screen to ring -- text only. */
    fun showAiSuggestionTextOnly(message: String) {
        ensureAttached()
        view.mode = OverlayView.Mode.AI_SUGGESTION
        view.targetRect = null
        view.message = message
    }

    fun showStuck(message: String) {
        ensureAttached()
        view.mode = OverlayView.Mode.STUCK
        view.targetRect = null
        view.message = message
    }

    fun showLost() {
        ensureAttached()
        view.mode = OverlayView.Mode.LOST
        view.targetRect = null
        view.message = "Lost track of where we are. Try going back or opening the app again."
    }

    fun showFinished() {
        ensureAttached()
        view.mode = OverlayView.Mode.FINISHED
        view.targetRect = null
        view.message = "All done!"
    }

    fun hide() {
        helpButton.hide()
        if (!attached) return
        try {
            view.stopAnimating()
            windowManager.removeView(view)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not remove overlay", t)
        } finally {
            attached = false
        }
    }

    private fun ensureAttached() {
        helpButton.show { onHelpTapped?.invoke() }
        if (attached) return
        try {
            windowManager.addView(view, layoutParams)
            attached = true
            view.startAnimating()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not attach overlay", t)
        }
    }

    companion object {
        private const val TAG = "OverlayController"
    }
}
