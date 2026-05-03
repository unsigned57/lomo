package com.lomo.ui.component.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: LazyListScrollRequestDispatcher.
 * - Behavior focus: dragging the scrollbar emits many nearby targets; the LazyList should only
 *   chase the newest target instead of applying a backlog of stale scrollToItem requests.
 * - Observable outcomes: launched target sequence and cancellation state of prior requests.
 * - Red phase: Fails before the fix because scrollbar drag dispatch launches every scrollToItem
 *   request independently and has no latest-wins cancellation policy.
 * - Excludes: coroutine scheduler timing, Compose pointer input, and LazyListState internals.
 */
class DraggableScrollbarScrollRequestDispatcherTest {
    @Test
    fun `dispatch cancels the previous in-flight request before launching the latest target`() {
        val launchedRequests = mutableListOf<RecordingScrollRequest>()
        val dispatcher =
            LazyListScrollRequestDispatcher { target ->
                RecordingScrollRequest(target).also(launchedRequests::add)
            }

        dispatcher.dispatch(LazyListScrollTarget(index = 10, scrollOffsetPx = 0))
        dispatcher.dispatch(LazyListScrollTarget(index = 40, scrollOffsetPx = 12))

        assertEquals(
            listOf(
                LazyListScrollTarget(index = 10, scrollOffsetPx = 0),
                LazyListScrollTarget(index = 40, scrollOffsetPx = 12),
            ),
            launchedRequests.map { it.target },
        )
        assertTrue(launchedRequests.first().isCancelled)
        assertFalse(launchedRequests.last().isCancelled)
    }

    @Test
    fun `cancel active request only cancels the newest launched target`() {
        val launchedRequests = mutableListOf<RecordingScrollRequest>()
        val dispatcher =
            LazyListScrollRequestDispatcher { target ->
                RecordingScrollRequest(target).also(launchedRequests::add)
            }

        dispatcher.dispatch(LazyListScrollTarget(index = 3, scrollOffsetPx = 0))
        dispatcher.dispatch(LazyListScrollTarget(index = 6, scrollOffsetPx = 80))
        dispatcher.cancelActiveRequest()

        assertTrue(launchedRequests[0].isCancelled)
        assertTrue(launchedRequests[1].isCancelled)
    }

    private class RecordingScrollRequest(
        val target: LazyListScrollTarget,
    ) : LazyListScrollRequest {
        var isCancelled: Boolean = false
            private set

        override fun cancel() {
            isCancelled = true
        }
    }
}
