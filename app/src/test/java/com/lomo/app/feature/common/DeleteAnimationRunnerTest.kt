package com.lomo.app.feature.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
