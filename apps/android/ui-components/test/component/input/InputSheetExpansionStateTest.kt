package com.lomo.ui.component.input

import androidx.compose.animation.core.tween
import androidx.compose.runtime.BroadcastFrameClock
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.abs

/*
 * Behavior Contract:
 * Capability: one owner drives the compact ⇄ long-form expansion for the toggle, back and drag gestures;
 *   owning layer: ui-components input; priority: P1.
 * Scenarios:
 * - Given a compact sheet, when it is told to expand, then the expansion rises across several frames and settles at 1.
 * - Given an expansion in flight, when the target reverses, then motion continues from the current value without
 *   a jump and settles at 0 (the old owner swapped to a second, independently timed settle transition).
 * - Given a measured drag span, when the finger drags up by half the span, then the expansion follows to 0.5,
 *   and a longer drag clamps to the long-form.
 * - Given no measured span, when a drag arrives, then the expansion does not move.
 * - Given a released drag, when the settle target resolves, then a fling beyond the threshold wins over position,
 *   and a slow release settles to the nearer form.
 * Observable outcomes: expansion samples per frame and the resolved settle target.
 * TDD proof: RED `./kotlin test --include-module=ui-components --include-classes='com.lomo.ui.component.input.InputSheetExpansionStateTest'`
 *   with a stub that snaps and ignores drags (the old surface had no drag path) failed the frame, reversal, drag
 *   and fling scenarios; GREEN after the mutex-owned animation and drag implementation.
 * Excludes: Compose gesture detection, spring tuning and device frame pacing.
 *
 * Test Change Justification:
 * Reason category: domain contract change — the eight-state presentation machine was replaced by one expansion.
 * Old behavior/assertion being replaced: InputSheetPresentationTimingTest asserted ExpandingToEdit/ExpandedEdit
 *   presentation states settling after registered child animations.
 * Why old assertion is no longer correct: there are no transitional presentation states left to settle; geometry
 *   and chrome read the expansion directly.
 * Coverage preserved by: "rises across frames and settles" (completion follows motion, not a timeout) and
 *   "reversal continues without a jump" (latest target wins) below.
 * Why this is not fitting the test to the implementation: it samples the user-visible expansion per frame.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InputSheetExpansionStateTest : UiComponentsFunSpec() {
    init {
        test("given a compact sheet when told to expand then the expansion rises across frames and settles") {
            runTest {
                val frames = FrameDriver(BroadcastFrameClock())
                val expansion = InputSheetExpansionState(expanded = false)
                val motion = launch(frames.clock) { expansion.animateTo(expanded = true, animationSpec = tween(160)) }
                val samples = frames.send(this, count = 14) { expansion.fraction }
                withClue("samples=$samples") {
                    samples.zipWithNext().all { (a, b) -> b >= a } shouldBe true
                    samples.count { it > 0f && it < 1f } shouldBeGreaterThanOrEqual 5
                    expansion.fraction shouldBe 1f
                }
                motion.isCompleted shouldBe true
            }
        }

        test("given an expansion in flight when the target reverses then motion continues without a jump") {
            runTest {
                val frames = FrameDriver(BroadcastFrameClock())
                val expansion = InputSheetExpansionState(expanded = false)
                launch(frames.clock) { expansion.animateTo(expanded = true, animationSpec = tween(400)) }
                frames.send(this, count = 8) { expansion.fraction }
                val beforeReversal = expansion.fraction
                withClue("in flight at $beforeReversal") {
                    beforeReversal shouldBeGreaterThan 0f
                    beforeReversal shouldBeLessThan 1f
                }
                launch(frames.clock) { expansion.animateTo(expanded = false, animationSpec = tween(400)) }
                val samples = frames.send(this, count = 40) { expansion.fraction }
                withClue("reversal from $beforeReversal sampled $samples") {
                    abs(samples.first() - beforeReversal) shouldBeLessThan 0.1f
                    expansion.fraction shouldBe 0f
                }
            }
        }

        test("given a measured drag span when the finger drags up then the expansion follows and clamps") {
            val expansion = InputSheetExpansionState(expanded = false)
            expansion.dragSpanPx = 400f

            expansion.dispatchRawDelta(-200f)
            expansion.fraction shouldBe 0.5f
            expansion.dispatchRawDelta(-1_000f)
            expansion.fraction shouldBe 1f
            expansion.dispatchRawDelta(100f)
            expansion.fraction shouldBe 0.75f
        }

        test("given no measured span when a drag arrives then the expansion does not move") {
            val expansion = InputSheetExpansionState(expanded = false)

            expansion.dispatchRawDelta(-200f)

            expansion.fraction shouldBe 0f
        }

        test("given a released drag when the settle target resolves then fling beats position and slow release snaps") {
            val threshold = 300f
            withClue("upward fling from mostly compact expands") {
                resolveInputSheetSettleTarget(
                    0.2f,
                    velocityPxPerSecond = -2_000f,
                    velocityThresholdPxPerSecond = threshold,
                ) shouldBe true
            }
            withClue("downward fling from mostly expanded collapses") {
                resolveInputSheetSettleTarget(
                    0.8f,
                    velocityPxPerSecond = 2_000f,
                    velocityThresholdPxPerSecond = threshold,
                ) shouldBe false
            }
            withClue("slow release settles to the nearer form") {
                resolveInputSheetSettleTarget(
                    0.6f,
                    velocityPxPerSecond = -50f,
                    velocityThresholdPxPerSecond = threshold,
                ) shouldBe true
                resolveInputSheetSettleTarget(
                    0.4f,
                    velocityPxPerSecond = 50f,
                    velocityThresholdPxPerSecond = threshold,
                ) shouldBe false
            }
        }
    }
}

/** Drives a test frame clock in 16ms steps and samples a value after each frame. */
private class FrameDriver(val clock: BroadcastFrameClock) {
    private var frameTimeNanos = 0L

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun send(scope: TestScope, count: Int, sample: () -> Float): List<Float> = List(count) {
        scope.runCurrent()
        frameTimeNanos += FRAME_NANOS
        clock.sendFrame(frameTimeNanos)
        scope.runCurrent()
        sample()
    }

    private companion object {
        const val FRAME_NANOS = 16_000_000L
    }
}
