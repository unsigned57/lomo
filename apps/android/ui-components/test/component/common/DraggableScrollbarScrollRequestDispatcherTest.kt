package com.lomo.ui.component.common

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Scenarios:
 * - Given an in-flight scroll request, when a newer target is dispatched, then the previous request is
 *   cancelled before the new request runs.
 * - Given successive requests, when cancellation is requested, then only the newest active request is
 *   cancelled.
 * - Unit under test: LazyListScrollRequestDispatcher.
 * - Capability: dragging the scrollbar emits many nearby targets; the LazyList should only
 *   chase the newest target instead of applying a backlog of stale scrollToItem requests.
 * - Observable outcomes: launched target sequence and cancellation state of prior requests.
 * - TDD proof: Fails before the fix because scrollbar drag dispatch launches every scrollToItem
 *   request independently and has no latest-wins cancellation policy.
 * - Excludes: coroutine scheduler timing, Compose pointer input, and LazyListState internals.
 *
 * Test Change Justification:
 * Reason category: Mechanical formatting and import alignment for DraggableScrollbarScrollRequestDispatcher.
 * Old behavior/assertion being replaced: None; existing scenario bodies and expected results are retained.
 * Why old assertion is no longer correct: Existing assertions remain correct. Layout, imports, and equivalent
 * fixture declaration syntax do not change their behavior.
 * Coverage preserved by: The existing scenarios, including dispatch cancels the previous in-flight request
 * before launching the latest target; cancel active request only cancels the newest launched target.
 * Why this is not fitting the test to the implementation: No expected result is relaxed or replaced; the
 * original behavior locks remain executable.
 */
class DraggableScrollbarScrollRequestDispatcherTest : UiComponentsFunSpec() {
    init {
        test("dispatch cancels the previous in-flight request before launching the latest target") {
            val launchedRequests = mutableListOf<RecordingScrollRequest>()
            val dispatcher =
                LazyListScrollRequestDispatcher { target ->
                    RecordingScrollRequest(target).also(launchedRequests::add)
                }

            dispatcher.dispatch(LazyListScrollTarget(index = 10, scrollOffsetPx = 0))
            dispatcher.dispatch(LazyListScrollTarget(index = 40, scrollOffsetPx = 12))

            (launchedRequests.map { it.target }) shouldBe (
                listOf(
                    LazyListScrollTarget(index = 10, scrollOffsetPx = 0),
                    LazyListScrollTarget(index = 40, scrollOffsetPx = 12),
                )
                )
            (launchedRequests.first().isCancelled) shouldBe true
            (launchedRequests.last().isCancelled) shouldBe false
        }
    }

    init {
        test("cancel active request only cancels the newest launched target") {
            val launchedRequests = mutableListOf<RecordingScrollRequest>()
            val dispatcher =
                LazyListScrollRequestDispatcher { target ->
                    RecordingScrollRequest(target).also(launchedRequests::add)
                }

            dispatcher.dispatch(LazyListScrollTarget(index = 3, scrollOffsetPx = 0))
            dispatcher.dispatch(LazyListScrollTarget(index = 6, scrollOffsetPx = 80))
            dispatcher.cancelActiveRequest()

            (launchedRequests[0].isCancelled) shouldBe true
            (launchedRequests[1].isCancelled) shouldBe true
        }
    }

    private class RecordingScrollRequest(val target: LazyListScrollTarget) : LazyListScrollRequest {
        var isCancelled: Boolean = false
            private set

        override fun cancel() {
            isCancelled = true
        }
    }
}
