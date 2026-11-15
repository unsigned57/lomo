package com.lomo.ui.component.common

import android.os.Trace
import android.util.Log
import androidx.compose.animation.core.tween
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * Capability: scrollbar position animation updates without recomposing its host; owner: ui-components; P1.
 * Scenarios:
 * - Given a restored position, when mounted, then the thumb starts at that position immediately.
 * - Given a mounted scrollbar, when scroll targets change repeatedly, then animation reaches the latest
 *   target without recomposing the host, and disposing cancels observation.
 * Observable outcomes: committed composition count and animated offset under a real Recomposer.
 * TDD proof: reports/manual/home-scroll/recomposition-red.log and recomposition-green.log.
 * Excludes: Android drawing, pointer dispatch and physical-device frame deadlines.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScrollbarRecompositionTest : UiComponentsFunSpec() {
    init {
        test("given a mounted scrollbar when position changes then only animation observes each target") {
            mockkStatic(Trace::class, Log::class)
            every { Trace.beginSection(any()) } just Runs
            every { Trace.endSection() } just Runs
            every { Log.e(any(), any()) } returns 0
            try {
                runTest {
                    val frames = BroadcastFrameClock()
                    val recomposer = Recomposer(coroutineContext + frames)
                    val runner = backgroundScope.launch(frames) { recomposer.runRecomposeAndApplyChanges() }
                    val composition = Composition(ScrollbarApplier(), recomposer)
                    val target = mutableFloatStateOf(20f)
                    var offset: State<Float>? = null
                    var compositions = 0
                    var frame = 0L
                    fun advanceFrames(count: Int) {
                        repeat(count) {
                            Snapshot.sendApplyNotifications()
                            runCurrent()
                            frame += 16_000_000L
                            frames.sendFrame(frame)
                            runCurrent()
                        }
                    }
                    try {
                        composition.setContent {
                            offset = rememberScrollbarThumbOffset({ target.floatValue }, tween(64))
                            SideEffect { compositions += 1 }
                        }
                        advanceFrames(2)
                        checkNotNull(offset).value shouldBe 20f
                        val initialCompositions = compositions
                        listOf(40f, 120f, 240f).forEach { position ->
                            target.floatValue = position
                            advanceFrames(2)
                        }
                        advanceFrames(12)
                        checkNotNull(offset).value shouldBe 240f
                        compositions shouldBe initialCompositions
                        composition.dispose()
                        target.floatValue = 400f
                        advanceFrames(8)
                        checkNotNull(offset).value shouldBe 240f
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

private class ScrollbarApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = error("Unexpected UI node")
    override fun insertBottomUp(index: Int, instance: Unit) = error("Unexpected UI node")
    override fun remove(index: Int, count: Int) = error("Unexpected UI node")
    override fun move(from: Int, to: Int, count: Int) = error("Unexpected UI node")
    override fun onClear() = Unit
}
