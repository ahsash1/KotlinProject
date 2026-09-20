package org.example.project.guidance

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface GuidanceState {
    data object Idle : GuidanceState

    /** Pointing [element] out to the user as the target for [step]. */
    data class Pointing(
        val task: GuidanceTask,
        val stepIndex: Int,
        val step: GuidanceStep,
        val element: ScreenElement,
    ) : GuidanceState

    /** Nothing has matched [step] for a while; still on the same task/step. */
    data class Stuck(
        val task: GuidanceTask,
        val stepIndex: Int,
        val step: GuidanceStep,
    ) : GuidanceState

    /** Nothing in the whole task has matched anything on screen for a while. */
    data class Lost(val task: GuidanceTask) : GuidanceState

    data class Finished(val task: GuidanceTask) : GuidanceState
}

/**
 * Drives one [GuidanceTask] against a stream of screen snapshots. Pure
 * Kotlin, no platform APIs, no wall-clock reads of its own -- callers pass
 * the current time so this stays trivially unit-testable.
 */
class GuidanceEngine(
    private val stuckAfterMillis: Long = 8_000L,
    private val lostAfterMillis: Long = 15_000L,
) {
    private val _state = MutableStateFlow<GuidanceState>(GuidanceState.Idle)
    val state: StateFlow<GuidanceState> = _state.asStateFlow()

    private var task: GuidanceTask? = null
    private var stepIndex: Int = 0
    private var lastMatchAtMillis: Long = 0L

    fun start(task: GuidanceTask, nowMillis: Long) {
        this.task = task
        stepIndex = 0
        lastMatchAtMillis = nowMillis
        // Idle, not Stuck: no screen has been observed yet. A caller that re-renders
        // state.value on a timer (not just in response to onScreenElements) must not
        // announce "stuck on step 0" before the target app has even opened.
        _state.value = GuidanceState.Idle
    }

    fun stop() {
        task = null
        stepIndex = 0
        _state.value = GuidanceState.Idle
    }

    /** Call after any period the screen wasn't observed (e.g. the user left the target app). */
    fun resume(nowMillis: Long) {
        lastMatchAtMillis = nowMillis
    }

    /**
     * Feed the latest screen snapshot. Advances/points/marks stuck or lost as
     * appropriate; has no effect if no task is active.
     */
    fun onScreenElements(elements: List<ScreenElement>, nowMillis: Long) {
        val currentTask = task ?: return

        while (true) {
            if (stepIndex >= currentTask.steps.size) {
                _state.value = GuidanceState.Finished(currentTask)
                return
            }

            val match = findMatchFrom(currentTask, stepIndex, elements)
            if (match != null) {
                val (matchedIndex, matchedElement) = match
                stepIndex = matchedIndex
                lastMatchAtMillis = nowMillis
                val target = resolveClickableTarget(matchedElement, elements)
                _state.value = GuidanceState.Pointing(currentTask, stepIndex, currentTask.steps[stepIndex], target)
                return
            }

            val step = currentTask.steps[stepIndex]
            val elapsed = nowMillis - lastMatchAtMillis

            if (step.optional && elapsed >= stuckAfterMillis) {
                // Never appeared and never will on this device -- move on.
                stepIndex++
                lastMatchAtMillis = nowMillis
                continue
            }

            if (elapsed >= lostAfterMillis) {
                _state.value = GuidanceState.Lost(currentTask)
            } else if (elapsed >= stuckAfterMillis) {
                _state.value = GuidanceState.Stuck(currentTask, stepIndex, step)
            }
            // Below the stuck threshold: leave the last emitted state as-is to avoid flicker.
            return
        }
    }

    private fun findMatchFrom(
        task: GuidanceTask,
        fromIndex: Int,
        elements: List<ScreenElement>,
    ): Pair<Int, ScreenElement>? {
        for (i in fromIndex until task.steps.size) {
            val element = task.steps[i].findElement(elements)
            if (element != null) return i to element
        }
        return null
    }
}
