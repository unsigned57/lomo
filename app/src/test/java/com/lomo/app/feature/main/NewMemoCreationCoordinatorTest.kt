package com.lomo.app.feature.main

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: new-memo creation coordination before list insertion.
 * - Behavior focus: a new memo should be created immediately when already at the absolute top without forcing an
 *   extra top-anchor jump, otherwise only after the list has directly jumped back to the absolute top; prepend
 *   creation must not schedule a second top-anchor step after that top recovery.
 * - Observable outcomes: direct top-recovery invocation count, absence of extra anchor pinning, creation
 *   ordering, and single-submission behavior under overlap.
 * - Red phase: Fails before the fix because NewMemoCreationCoordinator still invokes a separate top-anchor pin
 *   step after top recovery, which keeps the prepend flow dependent on an extra anchor phase instead of
 *   proceeding directly into the staged insert animation.
 * - Excludes: Compose recomposition, LazyList animation timing internals, and repository persistence.
 */
/*
 * Test Change Justification:
 * - Reason category: product contract changed.
 * - Exact behavior/assertion being replaced: the existing off-top test required a `pin` phase after the list had
 *   already recovered to the absolute top.
 * - Why the previous assertion is no longer correct: the requested prepend flow is now "jump to top if needed,
 *   then create and animate". Keeping a second anchor phase makes the implementation depend on extra repinning
 *   that the new animation order no longer permits.
 * - Retained/new coverage: the tests still protect top recovery ordering and overlap rejection, while now
 *   forbidding redundant post-recovery pinning in both the already-at-top and away-from-top paths.
 * - Why this is not changing the test to fit the implementation: the new assertion matches the reported
 *   user-visible prepend sequence rather than an internal refactor detail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewMemoCreationCoordinatorTest {
    @Test
    fun `submit creates immediately without scrolling or repinning when list is already at top`() =
        runTest {
            val events = mutableListOf<String>()
            val coordinator =
                NewMemoCreationCoordinator<String>(
                    scope = backgroundScope,
                    isListAtAbsoluteTop = { true },
                    scrollListToAbsoluteTop = { events += "scroll" },
                    createMemo = { content -> events += "create:$content" },
                )

            val accepted = coordinator.submit("memo body")
            advanceUntilIdle()

            assertTrue(accepted)
            assertEquals(listOf("create:memo body"), events)
        }

    @Test
    fun `submit jumps to top and then creates when list is away from top without a second anchor phase`() =
        runTest {
            val events = mutableListOf<String>()
            var atTop = false
            val coordinator =
                NewMemoCreationCoordinator<String>(
                    scope = backgroundScope,
                    isListAtAbsoluteTop = { atTop },
                    scrollListToAbsoluteTop = {
                        events += "scroll"
                        atTop = true
                    },
                    createMemo = { content -> events += "create:$content" },
                )

            val accepted = coordinator.submit("memo body")
            advanceUntilIdle()

            assertTrue(accepted)
            assertEquals(listOf("scroll", "create:memo body"), events)
        }

    @Test
    fun `submit ignores overlapping requests while waiting for scroll completion`() =
        runTest {
            val scrollGate = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val coordinator =
                NewMemoCreationCoordinator<String>(
                    scope = backgroundScope,
                    isListAtAbsoluteTop = { false },
                    scrollListToAbsoluteTop = {
                        events += "scroll"
                        scrollGate.await()
                    },
                    createMemo = { content -> events += "create:$content" },
                )

            val firstAccepted = coordinator.submit("first")
            val secondAccepted = coordinator.submit("second")
            scrollGate.complete(Unit)
            advanceUntilIdle()

            assertTrue(firstAccepted)
            assertEquals(false, secondAccepted)
            assertEquals(listOf("scroll", "create:first"), events)
        }
}
