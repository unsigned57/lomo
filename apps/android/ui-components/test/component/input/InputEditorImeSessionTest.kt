package com.lomo.ui.component.input

import android.os.Trace
import android.util.Log
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextRange
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
 * Capability: text and metadata updates preserve the editor transformation session; owner: ui-components; P1.
 * Scenarios:
 * - Given an active projection, when repeated deletion changes the source, then its session stays mounted.
 * - Given updated hidden ranges, when projecting the new buffer, then current metadata is hidden.
 * Observable outcomes: projection session starts/disposals and displayed text under a real Recomposer.
 * TDD proof: ./kotlin test --include-module=ui-components --include-classes=com.lomo.ui.component.input.InputEditorImeSessionTest;
 * RED: session starts becomes 2 after deletion; GREEN: one session across deletion and metadata changes.
 * Evidence: target/lomo/reports/manual/ime-upgrade/.
 * Excludes: Android input connection internals and physical keyboard behavior; device long-press is separate evidence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InputEditorImeSessionTest : UiComponentsFunSpec() {
    init {
        test("given an active editor when deleting and updating metadata then the projection session stays mounted") {
            mockkStatic(Trace::class, Log::class)
            every { Trace.beginSection(any()) } just Runs
            every { Trace.endSection() } just Runs
            every { Log.e(any(), any()) } returns 0
            try {
                runTest {
                    val frames = BroadcastFrameClock()
                    val recomposer = Recomposer(coroutineContext + frames)
                    val runner = backgroundScope.launch(frames) { recomposer.runRecomposeAndApplyChanges() }
                    val composition = Composition(ProjectionApplier(), recomposer)
                    val source = mutableStateOf("abcdef")
                    val ranges = mutableStateOf(emptyList<TextRange>())
                    var projection: InputEditorHiddenText? = null
                    var starts = 0
                    var disposals = 0
                    try {
                        composition.setContent {
                            val current = rememberInputEditorHiddenText(source.value, ranges.value)
                            projection = current
                            DisposableEffect(current) {
                                starts += 1
                                onDispose { disposals += 1 }
                            }
                        }
                        runCurrent()
                        listOf("abcde", "abcd", "abc", "ab", "a", "").forEachIndexed { index, text ->
                            source.value = text
                            Snapshot.sendApplyNotifications()
                            runCurrent()
                            frames.sendFrame((index + 1) * 16_000_000L)
                            runCurrent()
                            starts shouldBe 1
                            disposals shouldBe 0
                        }
                        source.value = "a<meta>b"
                        ranges.value = listOf(TextRange(1, 7))
                        Snapshot.sendApplyNotifications()
                        runCurrent()
                        frames.sendFrame(128_000_000L)
                        runCurrent()
                        val buffer = TextFieldState(source.value)
                        buffer.edit { with(checkNotNull(projection)) { transformOutput() } }
                        buffer.text.toString() shouldBe "ab"
                        starts shouldBe 1
                        disposals shouldBe 0
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

private class ProjectionApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = error("Unexpected UI node")
    override fun insertBottomUp(index: Int, instance: Unit) = error("Unexpected UI node")
    override fun remove(index: Int, count: Int) = error("Unexpected UI node")
    override fun move(from: Int, to: Int, count: Int) = error("Unexpected UI node")
    override fun onClear() = Unit
}
