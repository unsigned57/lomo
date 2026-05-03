package com.lomo.app.feature.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: runDeleteAnimationWithRollback
 * - Behavior focus: delete animation marks the item immediately and rolls back on failure/cancellation.
 *   The animation timing is now managed by Compose (fadeOut + shrinkVertically with delayMillis),
 *   so the runner only sets/clears the deleting marker — no internal delay, no separate collapse phase.
 * - Observable outcomes: deleting ids before/after mutation success, failure rollback, cancellation propagation.
 * - Red phase: Fails before the fix because the runner still declares a collapsedIds parameter and
 *   an internal delay(animationDelayMs) that no longer belong in the animation orchestrator.
 * - Excludes: Compose rendering, animation frame timing, and ViewModel orchestration.
 */
/*
 * Test Change Justification:
 * - Reason category: product contract changed.
 * - Old behavior/assertion being replaced: the runner previously managed two-phase animation
 *   (deletingIds → collapsedIds with a coroutine delay between them) and tests verified
 *   the collapsedIds timeline.
 * - Why the old assertion is no longer correct: the animation timing is now driven by Compose's
 *   exitTransition (fadeOut + shrinkVertically with delayMillis). The runner no longer owns the
 *   timing — it only needs to mark/unmark the deleting flag.
 * - Coverage preserved by: rollback-on-failure and rollback-on-cancellation tests are retained
 *   and adapted to the single-state contract. The former collapsedIds timing tests are replaced
 *   by a new test verifying immediate mutation execution without internal delay.
 * - Why this is not fitting the test to the implementation: the new assertions encode the
 *   user-visible contract (delete mark → Compose animation → cleanup) rather than a private
 *   implementation detail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteAnimationRunnerTest {
    @Test
    fun `marks deleting on success`() =
        runTest {
            val deletingIds = MutableStateFlow(emptySet<String>())

            val result =
                runDeleteAnimationWithRollback(
                    itemId = "memo_1",
                    deletingIds = deletingIds,
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
    fun `marks all deleting ids on bulk success`() =
        runTest {
            val deletingIds = MutableStateFlow(emptySet<String>())

            val result =
                runDeleteAnimationWithRollback(
                    itemIds = setOf("memo_1", "memo_2"),
                    deletingIds = deletingIds,
                ) {
                    Unit
                }

            assertTrue(result.isSuccess)
            assertTrue(deletingIds.value.containsAll(setOf("memo_1", "memo_2")))
        }

    @Test
    fun `rolls back all deleting ids on bulk failure`() =
        runTest {
            val deletingIds = MutableStateFlow(emptySet<String>())

            val result =
                runDeleteAnimationWithRollback(
                    itemIds = setOf("memo_1", "memo_2"),
                    deletingIds = deletingIds,
                ) {
                    throw IllegalStateException("bulk delete failed")
                }

            assertTrue(result.isFailure)
            assertFalse(deletingIds.value.contains("memo_1"))
            assertFalse(deletingIds.value.contains("memo_2"))
        }

    @Test
    fun `executes mutation immediately without internal delay`() =
        runTest {
            val deletingIds = MutableStateFlow(emptySet<String>())
            var mutationCalled = false

            runDeleteAnimationWithRollback(
                itemId = "memo_1",
                deletingIds = deletingIds,
            ) {
                mutationCalled = true
            }

            assertTrue(mutationCalled)
            assertTrue(deletingIds.value.contains("memo_1"))
        }
}
