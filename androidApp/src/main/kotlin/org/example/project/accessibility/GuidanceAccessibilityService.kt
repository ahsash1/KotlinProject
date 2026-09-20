package org.example.project.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import org.example.project.capture.CaptureLogger
import org.example.project.guidance.GuidanceEngine
import org.example.project.guidance.GuidanceState
import org.example.project.guidance.GuidanceTask
import org.example.project.guidance.ScreenElement
import org.example.project.notify.GuidanceNotifier
import org.example.project.overlay.OverlayController
import org.example.project.speech.SpeechController

/**
 * Reads whatever app is in front (any package -- never restricted), drives a
 * [GuidanceEngine] session against it, and renders the result as an overlay
 * + speech. Never taps anything; only points.
 */
class GuidanceAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val engine = GuidanceEngine()

    private lateinit var overlay: OverlayController
    private lateinit var speech: SpeechController
    private lateinit var notifier: GuidanceNotifier

    private var activeTask: GuidanceTask? = null
    private var pausedForOtherApp = false
    private var lastElements: List<ScreenElement> = emptyList()
    private var lastSpokenKey: String? = null

    /** Null when no walk is currently pending; set to the time of the first event in the current burst. */
    private var burstStartedAtMillis: Long? = null

    private val pendingWalk = Runnable {
        burstStartedAtMillis = null
        performWalk()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (activeTask != null && !pausedForOtherApp) {
                engine.onScreenElements(lastElements, now())
                render(engine.state.value)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlay = OverlayController(this)
        speech = SpeechController(this)
        notifier = GuidanceNotifier(this)
        handler.post(ticker)
        Log.i(TAG, "Guidance accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val now = now()
        Log.i(TAG, "event received type=${AccessibilityEvent.eventTypeToString(event.eventType)} pkg=${event.packageName} t=$now")

        val burstStart = burstStartedAtMillis ?: now.also { burstStartedAtMillis = it }
        handler.removeCallbacks(pendingWalk)
        if (now - burstStart >= MAX_DEBOUNCE_WAIT_MS) {
            // A continuous stream of events (e.g. a busy transition or list recomposition) would
            // otherwise keep resetting a plain debounce forever, leaving the overlay showing a
            // stale screen. Force a walk now instead of deferring again.
            handler.post(pendingWalk)
        } else {
            handler.postDelayed(pendingWalk, DEBOUNCE_MS)
        }
    }

    override fun onInterrupt() {
        // Required override; nothing to cancel mid-flight.
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (::overlay.isInitialized) overlay.hide()
        if (::speech.isInitialized) speech.shutdown()
        if (::notifier.isInitialized) notifier.cancel()
        instance = null
    }

    fun startTask(task: GuidanceTask) {
        val launch = packageManager.getLaunchIntentForPackage(task.targetPackage)
        if (launch == null) {
            Toast.makeText(this, "${task.title} needs an app that isn't installed on this device.", Toast.LENGTH_LONG).show()
            Log.w(TAG, "${task.targetPackage} is not installed")
            return
        }

        activeTask = task
        pausedForOtherApp = false
        lastSpokenKey = null
        engine.start(task, now())
        notifier.showActive(task.title)

        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(launch)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not launch ${task.targetPackage}", t)
        }
    }

    fun stopTask() {
        activeTask = null
        pausedForOtherApp = false
        lastSpokenKey = null
        engine.stop()
        overlay.hide()
        notifier.cancel()
    }

    private fun performWalk() {
        val root = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            null
        }
        val currentPackage = root?.packageName?.toString()
        val elements = try {
            NodeWalker.walk(root)
        } catch (t: Throwable) {
            Log.w(TAG, "walk failed", t)
            emptyList()
        }
        lastElements = elements
        Log.i(TAG, "walk executed pkg=$currentPackage elements=${elements.size} t=${now()}")

        if (captureModeEnabled && currentPackage != null) {
            CaptureLogger.dump(this, currentPackage, elements)
        }

        val task = activeTask ?: return

        // No exemption for our own package here: rootInActiveWindow always reflects whichever
        // window is genuinely foreground, regardless of which window's event triggered this walk,
        // so if it ever reports our own package, the user really is looking at our own UI (e.g.
        // the brief window between tapping a task card and the target app actually taking over)
        // and guidance should pause exactly as it would for any other non-target app.
        if (currentPackage != null && currentPackage != task.targetPackage) {
            if (!pausedForOtherApp) {
                pausedForOtherApp = true
                overlay.hide()
            }
            return
        }

        if (pausedForOtherApp) {
            pausedForOtherApp = false
            engine.resume(now())
        }

        engine.onScreenElements(elements, now())
        render(engine.state.value)
    }

    private fun render(state: GuidanceState) {
        when (state) {
            is GuidanceState.Idle -> overlay.hide()
            is GuidanceState.Pointing -> {
                overlay.showPointing(state.element, state.step.instruction)
                speakOnce("pointing:${state.stepIndex}", state.step.instruction)
            }
            is GuidanceState.Stuck -> {
                val text = state.step.scrollHint ?: state.step.instruction
                overlay.showStuck(text)
                speakOnce("stuck:${state.stepIndex}", text)
            }
            is GuidanceState.Lost -> {
                overlay.showLost()
                speakOnce("lost", "I'm not sure where we are. Try going back or opening the app again.")
            }
            is GuidanceState.Finished -> {
                overlay.showFinished()
                speakOnce("finished", "All done!")
                notifier.cancel()
            }
        }
    }

    private fun speakOnce(key: String, text: String) {
        if (lastSpokenKey == key) return
        lastSpokenKey = key
        speech.speak(text)
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val TAG = "GuidanceService"
        private const val DEBOUNCE_MS = 300L
        private const val MAX_DEBOUNCE_WAIT_MS = 1_000L
        private const val TICK_MS = 2_000L

        @Volatile
        var instance: GuidanceAccessibilityService? = null
            private set

        @Volatile
        var captureModeEnabled: Boolean = false
    }
}
