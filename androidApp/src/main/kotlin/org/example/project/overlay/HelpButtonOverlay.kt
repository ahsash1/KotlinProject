package org.example.project.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import kotlin.math.abs

/**
 * A small, genuinely TOUCHABLE overlay window -- unlike the main pointer/
 * banner overlay, which is deliberately FLAG_NOT_TOUCHABLE so it never
 * blocks interaction with the target app underneath. Draggable: it starts
 * near the bottom-right corner, but the target app's own UI can have
 * something important right where it lands, so the person can drag it out
 * of the way. A tap still fires normally; only a real drag moves it.
 */
class HelpButtonOverlay(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var attached = false

    private val button = Button(service).apply {
        text = "Help"
        textSize = 16f
        setPadding(32, 16, 32, 16)
        setBackgroundColor(Color.argb(235, 25, 118, 210))
        setTextColor(Color.WHITE)
    }

    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Deliberately NOT FLAG_NOT_TOUCHABLE -- this window must receive the tap/drag.
        // FLAG_NOT_FOCUSABLE keeps it from stealing IME/input focus from the target app.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        // TOP|START with absolute x/y (not BOTTOM|END insets) so drag math is a plain
        // add-the-finger-delta, no sign flipping. Rough bottom-right starting position;
        // exact placement doesn't matter since it's immediately draggable anyway.
        gravity = Gravity.TOP or Gravity.START
        val metrics = service.resources.displayMetrics
        x = (metrics.widthPixels - APPROX_BUTTON_WIDTH_PX).coerceAtLeast(0)
        y = (metrics.heightPixels - APPROX_BUTTON_HEIGHT_PX - BOTTOM_MARGIN_PX).coerceAtLeast(0)
    }

    fun show(onTapped: () -> Unit) {
        setupTouchHandling(onTapped)
        if (attached) return
        try {
            windowManager.addView(button, layoutParams)
            attached = true
        } catch (t: Throwable) {
            Log.w(TAG, "Could not attach help button", t)
        }
    }

    fun hide() {
        if (!attached) return
        try {
            windowManager.removeView(button)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not remove help button", t)
        } finally {
            attached = false
        }
    }

    private fun setupTouchHandling(onTapped: () -> Unit) {
        var startLayoutX = 0
        var startLayoutY = 0
        var startTouchX = 0f
        var startTouchY = 0f
        var dragging = false

        button.setOnClickListener {
            // Fires only on a genuine click (no meaningful movement between down/up);
            // ACTION_MOVE handling below never calls this directly.
            onTapped()
        }

        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startLayoutX = layoutParams.x
                    startLayoutY = layoutParams.y
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    dragging = false
                    false // let the click listener still see this sequence
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startTouchX
                    val dy = event.rawY - startTouchY
                    if (!dragging && (abs(dx) > DRAG_THRESHOLD_PX || abs(dy) > DRAG_THRESHOLD_PX)) {
                        dragging = true
                    }
                    if (dragging) {
                        layoutParams.x = (startLayoutX + dx).toInt().coerceAtLeast(0)
                        layoutParams.y = (startLayoutY + dy).toInt().coerceAtLeast(0)
                        try {
                            windowManager.updateViewLayout(view, layoutParams)
                        } catch (t: Throwable) {
                            Log.w(TAG, "Could not reposition help button", t)
                        }
                        true // consume: this is a drag, not a click
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    companion object {
        private const val TAG = "HelpButtonOverlay"
        private const val DRAG_THRESHOLD_PX = 20f
        private const val APPROX_BUTTON_WIDTH_PX = 220
        private const val APPROX_BUTTON_HEIGHT_PX = 110
        private const val BOTTOM_MARGIN_PX = 160
    }
}
