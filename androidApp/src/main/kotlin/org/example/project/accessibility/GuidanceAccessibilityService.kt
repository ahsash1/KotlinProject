package org.example.project.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import org.example.project.BuildConfig
import org.example.project.capture.CaptureLogger
import org.example.project.guidance.ConfirmationMatcher
import org.example.project.guidance.FeatureFlags
import org.example.project.guidance.GuidanceEngine
import org.example.project.guidance.GuidanceState
import org.example.project.guidance.GuidanceTask
import org.example.project.guidance.LostRescueResult
import org.example.project.guidance.LostRescuer
import org.example.project.guidance.NoOpLostRescuer
import org.example.project.guidance.ScreenElement
import org.example.project.guidance.matches
import org.example.project.guidance.resolveClickableTarget
import org.example.project.llm.AnthropicLostRescuer
import org.example.project.llm.HelpResult
import org.example.project.llm.OpenAiHelpService
import org.example.project.notify.GuidanceNotifier
import org.example.project.overlay.OverlayController
import org.example.project.speech.SpeechController
import org.example.project.speech.VoiceTaskRecognizer

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

    private var lostRescuer: LostRescuer = NoOpLostRescuer
    private var lostRescueAttempted = false
    private var lastKnownStepIndex = 0

    private val helpService = OpenAiHelpService(BuildConfig.OPENAI_API_KEY)
    private lateinit var helpRecognizer: VoiceTaskRecognizer
    private var helpModeActive = false
    private var helpModeCapturedState: GuidanceState? = null
    private var helpRequestInFlight = false

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
                renderIfNotInHelpMode()
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
        lostRescuer = if (BuildConfig.LLM_API_KEY.isNotBlank()) {
            AnthropicLostRescuer(BuildConfig.LLM_API_KEY)
        } else {
            NoOpLostRescuer
        }
        helpRecognizer = VoiceTaskRecognizer(this)
        overlay.onHelpTapped = ::onHelpTapped
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
        if (::helpRecognizer.isInitialized) helpRecognizer.destroy()
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
        lostRescueAttempted = false
        lastKnownStepIndex = 0
        clearHelpMode()
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
        clearHelpMode()
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
        renderIfNotInHelpMode()
    }

    /**
     * Normal rendering, gated by Help mode: while a Help request is in flight or its answer is
     * still showing, the underlying step-advance logic keeps running (onScreenElements above is
     * unconditional), but the overlay/speech output stays with whatever Help last showed --
     * UNLESS the STEP has actually moved on from where it was when Help started.
     *
     * Deliberately compares step index, not full state equality: GuidanceEngine's own stuck/lost
     * timers keep escalating in the background purely from elapsed time (listening + waiting on
     * the LLM can easily take several seconds), so Stuck(2) can become Lost(2) while Help is still
     * in flight even though nothing real changed. Treating that as "real progress" was a bug --
     * it let the red/orange banner silently stomp the purple Help answer. Only an actual step
     * index change, or reaching Finished, counts as real progress here.
     */
    private fun renderIfNotInHelpMode() {
        if (helpModeActive) {
            val capturedIndex = helpModeCapturedState?.let { stepIndexOf(it) }
            val currentState = engine.state.value
            val realProgressHappened = currentState is GuidanceState.Finished || stepIndexOf(currentState) != capturedIndex
            if (!realProgressHappened) {
                return
            }
            helpModeActive = false
            helpModeCapturedState = null
        }
        render(engine.state.value)
    }

    private fun stepIndexOf(state: GuidanceState): Int? = when (state) {
        is GuidanceState.Pointing -> state.stepIndex
        is GuidanceState.Stuck -> state.stepIndex
        is GuidanceState.Lost -> state.stepIndex
        is GuidanceState.Idle, is GuidanceState.Finished -> null
    }

    private fun render(state: GuidanceState) {
        if (state !is GuidanceState.Lost) {
            lostRescueAttempted = false
        }
        when (state) {
            is GuidanceState.Idle -> overlay.hide()
            is GuidanceState.Pointing -> {
                lastKnownStepIndex = state.stepIndex
                overlay.showPointing(state.element, state.step.instruction)
                speakOnce("pointing:${state.stepIndex}", state.step.instruction)
            }
            is GuidanceState.Stuck -> {
                lastKnownStepIndex = state.stepIndex
                val text = state.step.scrollHint ?: state.step.instruction
                overlay.showStuck(text)
                speakOnce("stuck:${state.stepIndex}", text)
            }
            is GuidanceState.Lost -> {
                lastKnownStepIndex = state.stepIndex
                // Always shown immediately, regardless of whether a rescue is attempted below --
                // never leave the user staring at nothing while a network call is in flight.
                overlay.showLost()
                speakOnce("lost", "I'm not sure where we are. Try going back or opening the app again.")
                attemptLostRescueIfNeeded(state)
            }
            is GuidanceState.Finished -> {
                overlay.showFinished()
                speakOnce("finished", "All done!")
                notifier.cancel()
            }
        }
    }

    /**
     * Fires at most once per Lost episode (reset whenever the engine leaves Lost). A rescue is
     * purely a transient, lower-confidence overlay on top of whatever GuidanceEngine is doing --
     * it never touches engine state, matchers, or stepIndex. If the real matcher-based flow
     * resolves things before or after this returns, that takes over exactly as if this had never
     * been attempted.
     */
    private fun attemptLostRescueIfNeeded(lostState: GuidanceState.Lost) {
        if (!FeatureFlags.lostRescueEnabled) return
        if (lostRescueAttempted) return
        lostRescueAttempted = true

        val remainingSteps = lostState.task.steps.drop(lostState.stepIndex)
        if (remainingSteps.isEmpty()) return

        val elementsSnapshot = lastElements
        lostRescuer.rescue(elementsSnapshot, remainingSteps) { result ->
            // Only apply if still Lost by the time the (possibly delayed) result arrives -- a
            // real match may have already resolved this in the meantime.
            if (engine.state.value !is GuidanceState.Lost) return@rescue
            when (result) {
                is LostRescueResult.Found -> {
                    val target = resolveClickableTarget(result.element, elementsSnapshot)
                    val hedged = "I'm not certain, but try: ${result.instruction}"
                    overlay.showAiSuggestion(target, hedged)
                    speakOnce("ai_rescue:${lostState.stepIndex}", hedged)
                }
                is LostRescueResult.Unclear, is LostRescueResult.Failed -> {
                    // Leave the existing Lost banner/speech as-is; nothing more to do.
                }
            }
        }
    }

    /**
     * User-initiated Help. Never touches GuidanceEngine, matchers, or step-advance logic --
     * purely an overlay on top of whatever's already happening. Ignored while a request is
     * already in flight; a fresh tap after a previous answer is showing is allowed (a natural
     * "ask something else" follow-up).
     */
    private fun onHelpTapped() {
        if (helpRequestInFlight) return
        val task = activeTask ?: return
        helpRequestInFlight = true

        if (!helpModeActive) {
            // Pause pointing/speaking: freeze what "normal" looks like right now so
            // renderIfNotInHelpMode() holds off until either Help finishes or real progress
            // happens on its own.
            helpModeActive = true
            helpModeCapturedState = engine.state.value
        }
        overlay.showAiSuggestionTextOnly("Listening...")

        helpRecognizer.listenOnce { transcript ->
            helpRequestInFlight = false
            if (activeTask == null) return@listenOnce // STOP was pressed while listening
            if (transcript.isNullOrBlank()) {
                resumeAfterHelpFailure()
                return@listenOnce
            }

            val remainingSteps = task.steps.drop(lastKnownStepIndex)
            val elementsSnapshot = lastElements
            helpRequestInFlight = true
            helpService.ask(transcript, elementsSnapshot, remainingSteps) { result ->
                helpRequestInFlight = false
                if (activeTask == null) return@ask // STOP was pressed while waiting on the LLM
                when (result) {
                    is HelpResult.Pointed -> {
                        val target = resolveClickableTarget(result.element, elementsSnapshot)
                        overlay.showAiSuggestion(target, result.instruction)
                        speech.speak(result.instruction)
                    }
                    is HelpResult.TextOnly -> {
                        overlay.showAiSuggestionTextOnly(result.instruction)
                        speech.speak(result.instruction)
                    }
                    is HelpResult.PerformType -> {
                        handlePerformableAction(task, result.element, result.instruction, result.consequential) { node ->
                            LiveActionPerformer.setText(node, result.text)
                        }
                    }
                    is HelpResult.PerformClick -> {
                        handlePerformableAction(task, result.element, result.instruction, result.consequential) { node ->
                            LiveActionPerformer.click(node)
                        }
                    }
                    is HelpResult.Failed -> {
                        resumeAfterHelpFailure()
                    }
                }
            }
        }
    }

    /**
     * Gate before ANY Help-performed action: matching either the task's own human-verified
     * [GuidanceTask.finalActionMatchers] (a backstop independent of the AI's own judgment) or the
     * AI's own "consequential" flag forces a spoken "say yes to confirm" round trip first. Only
     * reachable when the user's own words explicitly asked the AI to perform the action -- see
     * OpenAiHelpService's prompt -- never from the default "tell me what to do" path.
     */
    private fun handlePerformableAction(
        task: GuidanceTask,
        element: ScreenElement,
        instruction: String,
        consequentialFromAi: Boolean,
        perform: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean,
    ) {
        val isFinal = consequentialFromAi || task.finalActionMatchers.any { it.matches(element) }
        if (!isFinal) {
            executePerformableAction(element, instruction, perform)
            return
        }

        val confirmPrompt = "This will $instruction. Say yes to confirm, or say no to cancel."
        overlay.showAiSuggestionTextOnly(confirmPrompt)
        speech.speak(confirmPrompt)

        helpRequestInFlight = true
        helpRecognizer.listenOnce { confirmTranscript ->
            helpRequestInFlight = false
            if (activeTask == null) return@listenOnce // STOP was pressed while awaiting confirmation

            val confirmed = confirmTranscript?.let { ConfirmationMatcher.isAffirmative(it) } == true
            if (confirmed) {
                executePerformableAction(element, instruction, perform)
            } else {
                val cancelled = "Okay, not doing that."
                overlay.showAiSuggestionTextOnly(cancelled)
                speech.speak(cancelled)
                // Leave helpModeActive as-is: the cancellation message stays showing, same as any
                // other Help answer, until real progress happens or Help is tapped again.
            }
        }
    }

    private fun executePerformableAction(
        element: ScreenElement,
        instruction: String,
        perform: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean,
    ) {
        val root = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            null
        }
        val liveNode = try {
            NodeWalker.findLiveNode(root, element)
        } catch (t: Throwable) {
            null
        }

        val success = if (liveNode != null) {
            try {
                perform(liveNode)
            } catch (t: Throwable) {
                false
            } finally {
                @Suppress("DEPRECATION")
                try {
                    liveNode.recycle()
                } catch (t: Throwable) {
                    // recycle() is a no-op on modern API levels; ignore failures either way.
                }
            }
        } else {
            false
        }

        val message = if (success) instruction else "I couldn't do that automatically. $instruction"
        overlay.showAiSuggestionTextOnly(message)
        speech.speak(message)
    }

    private fun resumeAfterHelpFailure() {
        speech.speak("Let's try going back one screen.")
        clearHelpMode()
        render(engine.state.value)
    }

    private fun clearHelpMode() {
        helpModeActive = false
        helpModeCapturedState = null
        helpRequestInFlight = false
        if (::helpRecognizer.isInitialized) helpRecognizer.destroy()
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
