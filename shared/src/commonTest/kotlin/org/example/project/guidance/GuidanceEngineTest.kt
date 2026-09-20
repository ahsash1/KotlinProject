package org.example.project.guidance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private fun element(
    text: String? = null,
    contentDescription: String? = null,
    viewId: String? = null,
    left: Int = 0,
    top: Int = 0,
    right: Int = 100,
    bottom: Int = 50,
    isClickable: Boolean = true,
    isVisible: Boolean = true,
) = ScreenElement(
    text = text,
    contentDescription = contentDescription,
    viewId = viewId,
    className = "android.widget.TextView",
    left = left,
    top = top,
    right = right,
    bottom = bottom,
    isClickable = isClickable,
    isVisible = isVisible,
)

private fun task(vararg steps: GuidanceStep) = GuidanceTask(
    id = "t",
    title = "Test task",
    targetPackage = "com.example.target",
    steps = steps.toList(),
)

class GuidanceEngineTest {

    @Test
    fun `start is idle until the first real screen is observed`() {
        // Regression: a caller (the Android service) re-renders engine.state.value on a
        // background timer, independent of onScreenElements, so the target app has time to
        // actually open. start() must not leave anything Stuck/Pointing-shaped behind for that
        // timer to prematurely announce before any real screen was ever fed to the engine.
        val engine = GuidanceEngine()
        val step = GuidanceStep("Tap Continue", listOf(Matcher.TextEquals("Continue")))
        val t = task(step)

        engine.start(t, nowMillis = 0)

        assertEquals(GuidanceState.Idle, engine.state.value)
    }

    @Test
    fun `prefers clickable then largest then topmost among matches`() {
        val step = GuidanceStep(
            instruction = "Tap Wi-Fi",
            matchers = listOf(Matcher.TextContains("Wi-Fi")),
        )
        val notClickable = element(text = "Wi-Fi settings", isClickable = false, top = 0, right = 200, bottom = 100)
        val smallClickable = element(text = "Wi-Fi", isClickable = true, top = 50, right = 60, bottom = 70)
        val largeClickable = element(text = "Wi-Fi row", isClickable = true, top = 10, right = 300, bottom = 100)

        val found = step.findElement(listOf(notClickable, smallClickable, largeClickable))

        assertEquals(largeClickable, found)
    }

    @Test
    fun `advances to next step when current disappears and next appears`() {
        val engine = GuidanceEngine()
        val step1 = GuidanceStep("Tap A", listOf(Matcher.TextEquals("A")))
        val step2 = GuidanceStep("Tap B", listOf(Matcher.TextEquals("B")))
        val t = task(step1, step2)

        engine.start(t, nowMillis = 0)
        engine.onScreenElements(listOf(element(text = "A")), nowMillis = 100)
        assertEquals(0, (engine.state.value as GuidanceState.Pointing).stepIndex)

        // Screen changed: A is gone, B is here.
        engine.onScreenElements(listOf(element(text = "B")), nowMillis = 200)
        val pointing = assertIs<GuidanceState.Pointing>(engine.state.value)
        assertEquals(1, pointing.stepIndex)
    }

    @Test
    fun `skips ahead when a later step's element appears instead of the next one`() {
        val engine = GuidanceEngine()
        val step1 = GuidanceStep("Tap A", listOf(Matcher.TextEquals("A")))
        val step2 = GuidanceStep("Tap B", listOf(Matcher.TextEquals("B")))
        val step3 = GuidanceStep("Tap C", listOf(Matcher.TextEquals("C")))
        val t = task(step1, step2, step3)

        engine.start(t, nowMillis = 0)
        engine.onScreenElements(listOf(element(text = "A")), nowMillis = 100)

        // User jumped ahead: neither A nor B are on screen, but C is.
        engine.onScreenElements(listOf(element(text = "C")), nowMillis = 200)
        val pointing = assertIs<GuidanceState.Pointing>(engine.state.value)
        assertEquals(2, pointing.stepIndex)
    }

    @Test
    fun `optional step is skipped once it never appears`() {
        val engine = GuidanceEngine(stuckAfterMillis = 1_000L, lostAfterMillis = 5_000L)
        val optionalStep = GuidanceStep("Maybe here", listOf(Matcher.TextEquals("Ghost")), optional = true)
        val nextStep = GuidanceStep("Tap B", listOf(Matcher.TextEquals("B")))
        val t = task(optionalStep, nextStep)

        engine.start(t, nowMillis = 0)
        // Neither step's element is present yet, and we're still below the stuck threshold.
        engine.onScreenElements(listOf(element(text = "nothing relevant")), nowMillis = 500)
        assertEquals(GuidanceState.Idle, engine.state.value)

        // Past the stuck threshold, optional step should be skipped and B found immediately.
        engine.onScreenElements(listOf(element(text = "B")), nowMillis = 1_500)
        val pointing = assertIs<GuidanceState.Pointing>(engine.state.value)
        assertEquals(1, pointing.stepIndex)
    }

    @Test
    fun `stays on current step forever if its own matcher text reappears on the next screen`() {
        // Documents real behaviour found via Capture Mode: current-step-priority means a step
        // whose matcher text collides with something on a LATER screen will never advance, even
        // though a later step would also match. This is correct/intentional engine behaviour
        // (matches "advance when the NEXT step's element appears... not when current disappears");
        // the fix belongs in the task's matchers (pick something unique to that screen), not here.
        // See the header comment in shared/.../guidance/Tasks.kt for the real-world case this hit.
        val engine = GuidanceEngine()
        val navigateIn = GuidanceStep("Tap Wi-Fi", listOf(Matcher.TextEquals("Wi-Fi")))
        val pickNetwork = GuidanceStep("Pick a network", listOf(Matcher.TextEquals("Available networks")))
        val t = task(navigateIn, pickNetwork)

        engine.start(t, nowMillis = 0)
        // Screen 1 (Connections): only "Wi-Fi" is present.
        engine.onScreenElements(listOf(element(text = "Wi-Fi")), nowMillis = 100)
        assertEquals(0, (engine.state.value as GuidanceState.Pointing).stepIndex)

        // Screen 2 (destination): "Wi-Fi" text reappears (e.g. a toggle row label) alongside
        // "Available networks". The engine stays on step 0 instead of advancing to step 1.
        engine.onScreenElements(
            listOf(element(text = "Wi-Fi"), element(text = "Available networks")),
            nowMillis = 200,
        )
        val pointing = assertIs<GuidanceState.Pointing>(engine.state.value)
        assertEquals(0, pointing.stepIndex)
    }

    @Test
    fun `reports stuck then lost when nothing matches for a while`() {
        val engine = GuidanceEngine(stuckAfterMillis = 1_000L, lostAfterMillis = 3_000L)
        val step = GuidanceStep("Tap A", listOf(Matcher.TextEquals("A")))
        val t = task(step)

        engine.start(t, nowMillis = 0)
        // Still below the stuck threshold: nothing to announce yet.
        engine.onScreenElements(listOf(element(text = "unrelated")), nowMillis = 500)
        assertEquals(GuidanceState.Idle, engine.state.value)

        engine.onScreenElements(listOf(element(text = "unrelated")), nowMillis = 1_500)
        assertIs<GuidanceState.Stuck>(engine.state.value)

        engine.onScreenElements(listOf(element(text = "unrelated")), nowMillis = 3_500)
        assertIs<GuidanceState.Lost>(engine.state.value)
    }
}
