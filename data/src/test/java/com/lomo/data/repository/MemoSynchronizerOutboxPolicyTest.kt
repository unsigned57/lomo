package com.lomo.data.repository

import com.lomo.data.local.entity.MemoFileOutboxEntity
import com.lomo.data.local.entity.MemoFileOutboxOp
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: MemoSynchronizer
 * - Behavior focus: outbox drain policy during explicit refresh, including poisoned-item dropping,
 *   failure marking, and skipping refresh while outbox work remains pending.
 * - Observable outcomes: outbox ack/failure calls and whether refresh is invoked after drain attempts.
 * - Red phase: Fails in the full suite before the fix because these policy tests start the background
 *   outbox coordinator, which races with the synchronous refresh path and makes the assertions flaky.
 * - Excludes: background drain scheduling, Room DAO behavior, and retired legacy capture coordination.
 */
class MemoSynchronizerOutboxPolicyTest {
    @MockK(relaxed = true)
    private lateinit var refreshEngine: MemoRefreshEngine

    @MockK(relaxed = true)
    private lateinit var mutationHandler: MemoMutationHandler

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
    }

    @Test
    fun `refresh drops poisoned outbox item when retry limit is reached`() =
        runTest {
            val poisonedItem = outboxItem(id = 1L, retryCount = 5, operation = MemoFileOutboxOp.UPDATE)
            val healthyItem = outboxItem(id = 2L, retryCount = 0, operation = MemoFileOutboxOp.DELETE)
            val queue = ArrayDeque(listOf<MemoFileOutboxEntity?>(poisonedItem, healthyItem, null))

            coEvery { mutationHandler.nextMemoFileOutbox() } coAnswers { queue.removeFirstOrNull() }
            coEvery { mutationHandler.flushMemoFileOutbox(healthyItem) } returns true
            coEvery { mutationHandler.hasPendingMemoFileOutbox() } returns false

            val synchronizer =
                MemoSynchronizer(
                    refreshEngine = refreshEngine,
                    mutationHandler = mutationHandler,
                    startOutboxCoordinator = false,
                )
            synchronizer.refresh()

            coVerify(exactly = 1) { mutationHandler.acknowledgeMemoFileOutbox(poisonedItem.id) }
            coVerify(exactly = 0) { mutationHandler.flushMemoFileOutbox(poisonedItem) }
            coVerify(exactly = 1) { mutationHandler.flushMemoFileOutbox(healthyItem) }
            coVerify(exactly = 1) { mutationHandler.acknowledgeMemoFileOutbox(healthyItem.id) }
            coVerify(exactly = 1) { refreshEngine.refresh(null) }
        }

    @Test
    fun `refresh marks failed outbox item and skips refresh while pending`() =
        runTest {
            val failingItem = outboxItem(id = 11L, retryCount = 0, operation = MemoFileOutboxOp.UPDATE)
            val queue = ArrayDeque(listOf<MemoFileOutboxEntity?>(failingItem, null))

            coEvery { mutationHandler.nextMemoFileOutbox() } coAnswers { queue.removeFirstOrNull() }
            coEvery { mutationHandler.flushMemoFileOutbox(failingItem) } returns false
            coEvery { mutationHandler.hasPendingMemoFileOutbox() } returns true

            val synchronizer =
                MemoSynchronizer(
                    refreshEngine = refreshEngine,
                    mutationHandler = mutationHandler,
                    startOutboxCoordinator = false,
                )
            synchronizer.refresh()

            coVerify(exactly = 1) { mutationHandler.markMemoFileOutboxFailed(eq(failingItem.id), match { it is IllegalStateException }) }
            coVerify(exactly = 0) { mutationHandler.acknowledgeMemoFileOutbox(failingItem.id) }
            coVerify(exactly = 0) { refreshEngine.refresh(any()) }
        }

    private fun outboxItem(
        id: Long,
        retryCount: Int,
        operation: String,
    ): MemoFileOutboxEntity =
        MemoFileOutboxEntity(
            id = id,
            operation = operation,
            memoId = "memo-$id",
            memoDate = "2024_01_16",
            memoTimestamp = 1_700_000_000_000,
            memoRawContent = "- 10:00:00 test",
            newContent = "test",
            createRawContent = "test",
            retryCount = retryCount,
        )
}
