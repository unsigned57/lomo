package com.lomo.app.feature.common

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: runDeleteAnimationWithRollback
 * - Behavior focus: delete animation state transitions across fade, success, and rollback while collapsing list space as soon as the fade finishes.
 * - Observable outcomes: deleting ids, collapsed ids, and rollback behavior before and after mutation completion.
 * - Red phase: Fails before the fix because the runner leaves collapsed ids empty until the backing mutation completes, so the row sits fully transparent while still occupying layout space.
 * - Excludes: Compose rendering, list placement interpolation, and repository implementation internals.
 */
/*
 * Test Change Justification:
 * - Reason category: product contract changed.
 * - Old behavior/assertion being replaced: the runner previously asserted that collapsed ids must stay empty until the backing mutation completed.
 * - Why the previous assertion is no longer correct: the reported regression is a long transparent gap after the fade-out, so the layout must collapse when the fade completes instead of waiting on repository latency.
 * - Coverage preserved by: the updated tests still lock fade timing and rollback behavior, and now specifically protect the no-transparent-gap requirement.
 * - Why this is not changing the test to fit the implementation: the new assertions encode the user-visible delete timing requirement rather than a private refactor detail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteAnimationRunnerTest {
    @Test
    fun `keeps deleting marker on success`() =
        runTest {
            val deletingIds = MutableStateFlow(emptySet<String>())

            val result =
                runDeleteAnimationWithRollback(
                    itemId = "memo_1",
                    deletingIds = deletingIds,
                    animationDelayMs = 0L,
                ) {
                    Unit
                }

            assertTrue(result.isSuccess)
            assertTrue(deletingIds.value.contains("memo_1"))
        }

    @Test
    fun `rolls back deleting marker on failure`() =
        runTest {
            val deletingIds = MutableStateFlow(emptySet<String>())

            val result =
                runDeleteAnimationWithRollback(
                    itemId = "memo_1",
                    deletingIds = deletingIds,
                    animationDelayMs = 0L,
                ) {
                    throw IllegalStateException("delete failed")
                }

            assertTrue(result.isFailure)
            assertFalse(deletingIds.value.contains("memo_1"))
        }

    @Test
    fun `rolls back deleting marker on cancellation`() =
        runTest {
            val deletingIds = MutableStateFlow(emptySet<String>())

            var cancelled = false
            try {
                runDeleteAnimationWithRollback(
                    itemId = "memo_1",
                    deletingIds = deletingIds,
                    animationDelayMs = 0L,
                ) {
                    throw CancellationException("cancel")
                }
            } catch (_: CancellationException) {
                cancelled = true
            }

            assertTrue(cancelled)
            assertFalse(deletingIds.value.contains("memo_1"))
        }

    @Test
    fun `keeps all deleting markers on bulk success`() =
        runTest {
            val deletingIds = MutableStateFlow(emptySet<String>())

            val result =
                runDeleteAnimationWithRollback(
                    itemIds = setOf("memo_1", "memo_2"),
                    deletingIds = deletingIds,
                    animationDelayMs = 0L,
                ) {
                    Unit
                }

            assertTrue(result.isSuccess)
            assertTrue(deletingIds.value.containsAll(setOf("memo_1", "memo_2")))
        }

    @Test
    fun `rolls back all deleting markers on bulk failure`() =
        runTest {
            val deletingIds = MutableStateFlow(emptySet<String>())

            val result =
                runDeleteAnimationWithRollback(
                    itemIds = setOf("memo_1", "memo_2"),
                    deletingIds = deletingIds,
                    animationDelayMs = 0L,
                ) {
                    throw IllegalStateException("bulk delete failed")
                }

            assertTrue(result.isFailure)
            assertFalse(deletingIds.value.contains("memo_1"))
            assertFalse(deletingIds.value.contains("memo_2"))
        }

    @Test
    fun `marks collapsed ids once fade completes even when mutation is still running`() =
        runTest {
            val deletingIds = MutableStateFlow(emptySet<String>())
            val collapsedIds = MutableStateFlow(emptySet<String>())
            val finishMutation = CompletableDeferred<Unit>()

            backgroundScope.launch {
                runDeleteAnimationWithRollback(
                    itemId = "memo_1",
                    deletingIds = deletingIds,
                    collapsedIds = collapsedIds,
                    animationDelayMs = 300L,
                ) {
                    finishMutation.await()
                }
            }

            runCurrent()
            assertTrue(deletingIds.value.contains("memo_1"))
            assertFalse(collapsedIds.value.contains("memo_1"))

            advanceTimeBy(300L)
            runCurrent()

            assertTrue(collapsedIds.value.contains("memo_1"))
            assertTrue(deletingIds.value.contains("memo_1"))

            finishMutation.complete(Unit)
            runCurrent()
        }

    @Test
    fun `keeps collapsed ids empty when failure happens after fade`() =
        runTest {
            val deletingIds = MutableStateFlow(emptySet<String>())
            val collapsedIds = MutableStateFlow(emptySet<String>())
            val releaseFailure = CompletableDeferred<Unit>()

            val resultDeferred =
                backgroundScope.launch {
                    runDeleteAnimationWithRollback(
                        itemId = "memo_1",
                        deletingIds = deletingIds,
                        collapsedIds = collapsedIds,
                        animationDelayMs = 300L,
                    ) {
                        releaseFailure.await()
                        throw IllegalStateException("delete failed")
                    }
                }

            advanceTimeBy(300L)
            runCurrent()
            assertTrue(collapsedIds.value.contains("memo_1"))

            releaseFailure.complete(Unit)
            resultDeferred.join()
            runCurrent()

            assertFalse(deletingIds.value.contains("memo_1"))
            assertFalse(collapsedIds.value.contains("memo_1"))
        }
}
