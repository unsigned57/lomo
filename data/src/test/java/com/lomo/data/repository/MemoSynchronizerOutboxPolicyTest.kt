package com.lomo.data.repository

import com.lomo.data.local.entity.MemoFileOutboxEntity
import com.lomo.data.local.entity.MemoFileOutboxIdentityPolicy
import com.lomo.data.local.entity.MemoFileOutboxOp
import io.kotest.matchers.string.shouldStartWith
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec

/*
 * Behavior Contract:
 * - Unit under test: MemoSynchronizer (outbox drain + refresh coupling).
 * - Owning layer: data repository orchestration.
 * - Capability: a failed/stuck outbox flush must never freeze reconciliation nor halt the drain.
 *
 * Scenarios:
 * - Given a dead-lettered item ahead of a live item, when refresh drains, then the dead-letter is
 *   skipped (not flushed, not acknowledged) and the live item still flushes.
 * - Given only dead-lettered items remain, when refresh runs, then reconciliation proceeds (the
 *   outbox no longer reports pending work, so refresh is no longer frozen).
 * - Given a live item whose flush fails, when refresh drains, then the item is marked failed for
 *   retry and refresh is deferred while genuinely-pending work remains.
 * - Given a live PERMANENT_DELETE whose completion cannot finish, when refresh drains, then it is
 *   marked with the durable "completion:" failure prefix and refresh is deferred.
 *
 * Observable outcomes: flush/ack/markFailed calls and whether refreshEngine.refresh is invoked.
 *
 * Test Change Justification:
 * - Reason category: Behavior change (systemic fix).
 * - Old behavior/assertion being replaced: a retry-exhausted "completion" item halted the whole drain and
 *   `hasPendingMemoFileOutbox` counted it, freezing reconciliation indefinitely; a plain poisoned
 *   item was silently dropped. Those assertions encoded the data-loss/freeze defect.
 * - Why old assertion is no longer correct: dead-lettered items are now quarantined at the
 *   claim/pending boundary, so they neither halt the drain nor freeze refresh, and they are never
 *   silently acknowledged.
 * - Coverage preserved by: the new scenarios still verify live items flush, failed live items defer
 *   refresh, and PERMANENT_DELETE completion failures are durably marked.
 * - Why this is not fitting the test to the implementation: the contract is "a stuck write must not
 *   strand other writes or freeze reconciliation", asserted via observable flush/ack/refresh calls.
 *
 * TDD proof:
 * - Fails before the systemic fix because the old drain would freeze behind a dead-letter completion
 *   item and never invoke refreshEngine.refresh, which the new dead-letter quarantine prevents.
 *
 * Excludes: background drain scheduling, Room DAO claim filtering (covered at the DAO layer), and
 * the concrete reason a SAF write fails.
 */
class MemoSynchronizerOutboxPolicyTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("refresh skips dead-lettered item without halting and still flushes the next live item") {
            `refresh skips dead-lettered item without halting and still flushes the next live item`()
        }

        test("refresh proceeds when only dead-lettered items remain") {
            `refresh proceeds when only dead-lettered items remain`()
        }

        test("refresh marks failed live item and defers refresh while pending") {
            `refresh marks failed live item and defers refresh while pending`()
        }

        test("refresh marks permanent delete completion failure and defers refresh") {
            `refresh marks permanent delete completion failure and defers refresh`()
        }
    }

    @MockK(relaxed = true)
    private lateinit var refreshEngine: MemoRefreshEngine

    @MockK(relaxed = true)
    private lateinit var mutationHandler: MemoMutationHandler

    private fun setUp() {
        MockKAnnotations.init(this)
    }

    private fun `refresh skips dead-lettered item without halting and still flushes the next live item`() =
        runTest {
            val deadLetter =
                outboxItem(
                    id = 1L,
                    retryCount = MAX_OUTBOX_RETRIES,
                    operation = MemoFileOutboxOp.UPDATE,
                    lastError = "completion: stuck flush",
                )
            val liveItem = outboxItem(id = 2L, retryCount = 0, operation = MemoFileOutboxOp.DELETE)
            val queue = ArrayDeque(listOf<MemoFileOutboxEntity?>(deadLetter, liveItem, null))

            coEvery { mutationHandler.nextMemoFileOutbox() } coAnswers { queue.removeFirstOrNull() }
            coEvery { mutationHandler.flushMemoFileOutbox(liveItem) } returns true
            coEvery { mutationHandler.hasPendingMemoFileOutbox() } returns false

            synchronizer().refresh()

            coVerify(exactly = 0) { mutationHandler.flushMemoFileOutbox(deadLetter) }
            coVerify(exactly = 0) { mutationHandler.acknowledgeMemoFileOutbox(deadLetter.id) }
            coVerify(exactly = 1) { mutationHandler.flushMemoFileOutbox(liveItem) }
            coVerify(exactly = 1) { mutationHandler.acknowledgeMemoFileOutbox(liveItem.id) }
            coVerify(exactly = 1) { refreshEngine.refresh(null) }
        }

    private fun `refresh proceeds when only dead-lettered items remain`() =
        runTest {
            val deadLetter =
                outboxItem(
                    id = 12L,
                    retryCount = MAX_OUTBOX_RETRIES,
                    operation = MemoFileOutboxOp.CREATE,
                    lastError = "completion: version append unavailable",
                )
            val queue = ArrayDeque(listOf<MemoFileOutboxEntity?>(deadLetter, null))

            coEvery { mutationHandler.nextMemoFileOutbox() } coAnswers { queue.removeFirstOrNull() }
            coEvery { mutationHandler.hasPendingMemoFileOutbox() } returns false

            synchronizer().refresh()

            coVerify(exactly = 0) { mutationHandler.flushMemoFileOutbox(deadLetter) }
            coVerify(exactly = 0) { mutationHandler.acknowledgeMemoFileOutbox(deadLetter.id) }
            coVerify(exactly = 1) { refreshEngine.refresh(null) }
        }

    private fun `refresh marks failed live item and defers refresh while pending`() =
        runTest {
            val failingItem = outboxItem(id = 11L, retryCount = 0, operation = MemoFileOutboxOp.UPDATE)
            val queue = ArrayDeque(listOf<MemoFileOutboxEntity?>(failingItem, null))

            coEvery { mutationHandler.nextMemoFileOutbox() } coAnswers { queue.removeFirstOrNull() }
            coEvery { mutationHandler.flushMemoFileOutbox(failingItem) } returns false
            coEvery { mutationHandler.hasPendingMemoFileOutbox() } returns true

            synchronizer().refresh()

            coVerify(exactly = 1) {
                mutationHandler.markMemoFileOutboxFailed(eq(failingItem.id), match { it is IllegalStateException })
            }
            coVerify(exactly = 0) { mutationHandler.acknowledgeMemoFileOutbox(failingItem.id) }
            coVerify(exactly = 0) { refreshEngine.refresh(any()) }
        }

    private fun `refresh marks permanent delete completion failure and defers refresh`() =
        runTest {
            val livePermanentDelete = outboxItem(id = 13L, retryCount = 0, operation = MemoFileOutboxOp.PERMANENT_DELETE)
            var durableLastError: String? = null
            coEvery { mutationHandler.flushMemoFileOutbox(livePermanentDelete) } returns false
            coEvery {
                mutationHandler.markMemoFileOutboxFailed(
                    livePermanentDelete.id,
                    match {
                        durableLastError = it.message
                        true
                    },
                )
            } coAnswers { Unit }
            coEvery { mutationHandler.hasPendingMemoFileOutbox() } returns true
            coEvery { mutationHandler.nextMemoFileOutbox() } returnsMany listOf(livePermanentDelete, null)

            synchronizer().refresh()

            durableLastError.shouldStartWith("completion:")
            coVerify(exactly = 1) { mutationHandler.flushMemoFileOutbox(livePermanentDelete) }
            coVerify(exactly = 0) { mutationHandler.acknowledgeMemoFileOutbox(livePermanentDelete.id) }
            coVerify(exactly = 0) { refreshEngine.refresh(any()) }
        }

    private fun synchronizer(): MemoSynchronizer =
        MemoSynchronizer(
            refreshEngine = refreshEngine,
            mutationHandler = mutationHandler,
            outboxScope = immediateTestBackgroundScope(),
            startOutboxCoordinator = false,
        )

    private fun outboxItem(
        id: Long,
        retryCount: Int,
        operation: MemoFileOutboxOp,
        lastError: String? = null,
    ): MemoFileOutboxEntity {
        val identity =
            MemoFileOutboxIdentityPolicy.forOutboxOperation(
                operation = operation,
                memoId = "memo-$id",
                memoDate = "2024_01_16",
                memoRawContent = "- 10:00:00 test",
                newContent = "test",
                createRawContent = "test",
            )
        return MemoFileOutboxEntity(
            id = id,
            operation = operation,
            operationId = identity.operationId,
            idempotencyKey = identity.idempotencyKey,
            memoId = "memo-$id",
            memoDate = "2024_01_16",
            memoTimestamp = 1_700_000_000_000,
            memoRawContent = "- 10:00:00 test",
            newContent = "test",
            createRawContent = "test",
            retryCount = retryCount,
            lastError = lastError,
        )
    }
}
