package com.lomo.ui.component.input

import android.os.Trace
import android.util.Log
import io.mockk.every
import io.mockk.just
import io.mockk.Runs
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Behavior Contract:
 * Capability: editor presentation follows actual animation completion; owner: ui-components; P1.
 * Scenarios:
 * Given a registered animation that exceeds the old 300ms timeout, when 400ms elapse, then the
 * sheet remains in its expanding state; after all motion settles, it becomes expanded.
 * Given a reversed target during motion, when frames settle, then the latest compact target wins.
 * Observable outcomes: presentation state and animated extent from a real Compose Transition.
 * TDD proof: this spec fails with the old delay-driven owner before replacing its settle logic.
 * Excludes: Android layout, keyboard insets and device frame pacing.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InputSheetPresentationTimingTest : UiComponentsFunSpec() {
    init {
        test("given slow registered motion when time passes then completion waits for the animation and reversal wins") {
            mockkStatic(Trace::class, Log::class)
            every { Trace.beginSection(any()) } just Runs
            every { Trace.endSection() } just Runs
            every { Log.e(any(), any()) } returns 0
            try {
            runTest {
                val frames = BroadcastFrameClock()
                val recomposer = Recomposer(coroutineContext + frames)
                val composition = Composition(PresentationApplier(), recomposer)
                val expanded = mutableStateOf(false)
                var observed = InputSheetPresentationState.CompactEdit
                var extent = 0f
                val runner = backgroundScope.launch(frames) { recomposer.runRecomposeAndApplyChanges() }
                composition.setContent {
                    val transition = rememberInputSheetPresentationTransition(expanded.value, InputEditorDisplayMode.Edit)
                    val animation = transition.animateFloat(
                        transitionSpec = { tween(1_000) },
                        label = "MeasuredSurface",
                    ) { state ->
                        when (state.surfaceMotionStage()) {
                            InputSheetMotionStage.Compact, InputSheetMotionStage.Collapsing -> 0f
                            InputSheetMotionStage.Expanding, InputSheetMotionStage.Expanded -> 1f
                        }
                    }
                    observed = transition.targetState
                    extent = animation.value
                }
                suspend fun advanceFrames(count: Int) {
                    repeat(count) {
                        Snapshot.sendApplyNotifications()
                        runCurrent()
                        testScheduler.advanceTimeBy(20)
                        frames.sendFrame(testScheduler.currentTime * 1_000_000)
                        runCurrent()
                    }
                }
                try {
                    advanceFrames(2)
                    expanded.value = true
                    advanceFrames(20)
                    observed shouldBe InputSheetPresentationState.ExpandingToEdit
                    advanceFrames(60)
                    observed shouldBe InputSheetPresentationState.ExpandedEdit
                    extent shouldBe 1f
                    expanded.value = false
                    advanceFrames(5)
                    expanded.value = true
                    advanceFrames(5)
                    expanded.value = false
                    advanceFrames(80)
                    observed shouldBe InputSheetPresentationState.CompactEdit
                    extent shouldBe 0f
                } finally {
                    composition.dispose()
                    recomposer.close()
                    runner.cancel()
                }
            }
            } finally {
                unmockkStatic(Trace::class, Log::class)
            }
        }
    }
}

/** A runtime-only composition has no UI nodes; unexpected node creation is a test failure. */
private class PresentationApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = error("Unexpected UI node")
    override fun insertBottomUp(index: Int, instance: Unit) = error("Unexpected UI node")
    override fun remove(index: Int, count: Int) = error("Unexpected UI node")
    override fun move(from: Int, to: Int, count: Int) = error("Unexpected UI node")
    override fun onClear() = Unit
}
