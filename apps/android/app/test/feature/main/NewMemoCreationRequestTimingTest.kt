package com.lomo.app.feature.main

import android.os.Trace
import android.util.Log
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import com.lomo.app.feature.common.UiEventQueueCoordinator
import com.lomo.app.feature.memo.MemoEditorSubmissionId
import io.kotest.core.spec.style.FunSpec
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
 * Capability: accepted creation requests reach their presentation owner without waiting for a UI frame; owner: app; P1.
 * Scenarios:
 * - Given an active Compose owner and a paused frame clock, when a request enters its acknowledged queue,
 *   then it starts and consumes that id before a recomposition frame; later frames do not replay it.
 * - Given a disposed owner, when a request arrives, then the queue retains it for the next owner.
 * Observable outcomes: delivered request content, queue membership and invocation count under a real Recomposer.
 * TDD proof: request-frame-red.md records waiting for a frame before delivering; request-frame-green.log does not.
 * Evidence root: target/lomo/reports/manual/four-bugs/.
 * Excludes: durable write latency, physical frame pacing and AndroidX lifecycle filtering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewMemoCreationRequestTimingTest :
    FunSpec({
        test("given a paused frame clock when a creation request arrives then execution starts before recomposition") {
            mockkStatic(Trace::class, Log::class)
            every { Trace.beginSection(any()) } just Runs
            every { Trace.endSection() } just Runs
            every { Log.e(any(), any()) } returns 0
            try {
                runTest {
                    val frames = BroadcastFrameClock()
                    val recomposer = Recomposer(coroutineContext + frames)
                    val queue = UiEventQueueCoordinator<PendingNewMemoCreationRequest>(maxSize = 1)
                    val delivered = mutableListOf<String>()
                    val runner = backgroundScope.launch(frames) { recomposer.runRecomposeAndApplyChanges() }
                    var composition = Composition(CreationRequestApplier(), recomposer)
                    fun mount() {
                        composition.setContent {
                            NewMemoCreationRequestEffect(queue.events) { event ->
                                delivered += event.payload.content
                                queue.consume(event.id)
                            }
                        }
                    }
                    try {
                        mount()
                        runCurrent()
                        queue.enqueue(PendingNewMemoCreationRequest(1, MemoEditorSubmissionId(1L), "first"))
                        runCurrent()
                        // No sendFrame: storage and return-to-top must already have been admitted.
                        delivered shouldBe listOf("first")
                        queue.events.value shouldBe emptyList()
                        Snapshot.sendApplyNotifications()
                        runCurrent()
                        frames.sendFrame(16_000_000)
                        runCurrent()
                        delivered shouldBe listOf("first")

                        composition.dispose()
                        runCurrent()
                        queue.enqueue(PendingNewMemoCreationRequest(2, MemoEditorSubmissionId(2L), "next"))
                        runCurrent()
                        delivered shouldBe listOf("first")
                        queue.events.value.single().payload.content shouldBe "next"
                        composition = Composition(CreationRequestApplier(), recomposer)
                        mount()
                        runCurrent()
                        delivered shouldBe listOf("first", "next")
                        queue.events.value shouldBe emptyList()
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
    })

/** A runtime-only effect composition must never create layout nodes. */
private class CreationRequestApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = error("Unexpected UI node")
    override fun insertBottomUp(index: Int, instance: Unit) = error("Unexpected UI node")
    override fun remove(index: Int, count: Int) = error("Unexpected UI node")
    override fun move(from: Int, to: Int, count: Int) = error("Unexpected UI node")
    override fun onClear() = Unit
}
