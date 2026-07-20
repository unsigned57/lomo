package com.lomo.data.repository

/*
 * Behavior Contract:
 * - Unit under test: MemoOutboxDrainCoordinator write hard gate.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: outbox drain fails closed (no claim/flush) unless EngineReadiness is Ready and
 *   writes are not frozen. Process-start / refresh drain must not mutate workspace files during
 *   Opening/Recovery/switch freeze.
 *
 * Scenarios:
 * - Given write freeze, when refresh drains, then no outbox item is claimed or flushed.
 * - Given ReadOnlyRecovery, when refresh drains, then no outbox item is claimed or flushed.
 * - Given Ready and unfrozen, when refresh drains, then the live outbox item flushes.
 *
 * Observable outcomes: nextMemoFileOutbox / flushMemoFileOutbox invocation counts.
 * TDD proof: fails before MemoSynchronizer consults WorkspaceWriteAuthority in drainOutboxLocked.
 * Excludes: concrete markdown write contents and retry scheduling timing.
 */

import com.lomo.data.local.entity.MemoFileOutboxEntity
import com.lomo.data.local.entity.MemoFileOutboxIdentityPolicy
import com.lomo.data.local.entity.MemoFileOutboxOp
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.fakes.FakeEngineReadinessRepository
import com.lomo.domain.model.EngineReadiness
import io.kotest.matchers.shouldBe
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest

class MemoOutboxWriteGateTest : DataFunSpec() {
    init {
        beforeTest {
            MockKAnnotations.init(this)
        }

        test("given write freeze when refresh drains then outbox flush is skipped") {
            runTest {
                val freeze = ProcessWriteFreezeRepository()
                freeze.begin()
                val authority =
                    WorkspaceWriteAuthority(
                        engineReadinessRepository = FakeEngineReadinessRepository(),
                        writeFreezeRepository = freeze,
                    )
                coEvery { mutationHandler.nextMemoFileOutbox() } returns liveItem()
                coEvery { mutationHandler.hasPendingMemoFileOutbox() } returns true

                synchronizer(authority).refresh()

                coVerify(exactly = 0) { mutationHandler.nextMemoFileOutbox() }
                coVerify(exactly = 0) { mutationHandler.flushMemoFileOutbox(any()) }
                coVerify(exactly = 0) { refreshEngine.refresh(any()) }
            }
        }

        test("given non-Ready engine when refresh drains then outbox flush is skipped") {
            runTest {
                val authority =
                    WorkspaceWriteAuthority(
                        engineReadinessRepository =
                            FakeEngineReadinessRepository(
                                EngineReadiness.ReadOnlyRecovery(
                                    category = EngineReadiness.FailureCategory.PERMISSION,
                                    code = "saf_grant_revoked",
                                    retryDisposition = EngineReadiness.RetryDisposition.AFTER_USER_ACTION,
                                    diagnostic = "revoked",
                                ),
                            ),
                        writeFreezeRepository = ProcessWriteFreezeRepository(),
                    )
                coEvery { mutationHandler.nextMemoFileOutbox() } returns liveItem()
                coEvery { mutationHandler.hasPendingMemoFileOutbox() } returns true

                synchronizer(authority).refresh()

                coVerify(exactly = 0) { mutationHandler.nextMemoFileOutbox() }
                coVerify(exactly = 0) { mutationHandler.flushMemoFileOutbox(any()) }
                coVerify(exactly = 0) { refreshEngine.refresh(any()) }
            }
        }

        test("given Ready engine when refresh drains then live outbox item flushes") {
            runTest {
                val authority =
                    WorkspaceWriteAuthority(
                        engineReadinessRepository = FakeEngineReadinessRepository(),
                        writeFreezeRepository = ProcessWriteFreezeRepository(),
                    )
                val item = liveItem()
                coEvery { mutationHandler.nextMemoFileOutbox() } returnsMany listOf(item, null)
                coEvery { mutationHandler.flushMemoFileOutbox(item) } returns true
                coEvery { mutationHandler.hasPendingMemoFileOutbox() } returns false

                synchronizer(authority).refresh()

                coVerify(exactly = 1) { mutationHandler.flushMemoFileOutbox(item) }
                coVerify(exactly = 1) { mutationHandler.acknowledgeMemoFileOutbox(item.id) }
                coVerify(exactly = 1) { refreshEngine.refresh(null) }
            }
        }
    }

    @MockK(relaxed = true)
    private lateinit var refreshEngine: MemoRefreshEngine

    @MockK(relaxed = true)
    private lateinit var mutationHandler: MemoMutationHandler

    private fun synchronizer(writeAuthority: WorkspaceWriteAuthority): MemoSynchronizer =
        MemoSynchronizer(
            refreshEngine = refreshEngine,
            mutationHandler = mutationHandler,
            outboxScope = immediateTestBackgroundScope(),
            writeAuthority = writeAuthority,
            startOutboxCoordinator = false,
        )

    private fun liveItem(): MemoFileOutboxEntity {
        val identity =
            MemoFileOutboxIdentityPolicy.forOutboxOperation(
                operation = MemoFileOutboxOp.CREATE,
                memoId = "memo-1",
                memoDate = "2026_07_17",
                memoRawContent = "- 10:00:00 test",
                newContent = null,
                createRawContent = "- 10:00:00 test",
            )
        return MemoFileOutboxEntity(
            id = 1L,
            operation = MemoFileOutboxOp.CREATE,
            operationId = identity.operationId,
            idempotencyKey = identity.idempotencyKey,
            memoId = "memo-1",
            memoDate = "2026_07_17",
            memoTimestamp = 1_700_000_000_000,
            memoRawContent = "- 10:00:00 test",
            newContent = null,
            createRawContent = "- 10:00:00 test",
            retryCount = 0,
            lastError = null,
        )
    }
}
