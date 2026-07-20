package com.lomo.data.repository

/*
 * Behavior Contract:
 * - Unit under test: MemoMutationRepositoryImpl write hard gate.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: every memo write fails closed unless EngineReadiness is Ready and writes are not frozen.
 *
 * Scenarios:
 * - Given ReadOnlyRecovery, when saveMemo is requested, then no synchronizer write runs.
 * - Given Ready, when saveMemo is requested, then synchronizer is invoked.
 *
 * Observable outcomes: exception + synchronizer call count.
 * TDD proof: fails before engine readiness is required by MemoMutationRepositoryImpl.
 * Excludes: reminder scheduling and outbox drain.
 */

import com.lomo.data.local.dao.MemoPinDao
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.fakes.FakeEngineReadinessRepository
import com.lomo.data.testing.fakes.FakeReminderCoordinator
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoQueryRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

class MemoMutationWriteGateTest : DataFunSpec() {
    init {
        test("given non-Ready engine when saveMemo runs then write fails closed") {
            runTest {
                val synchronizer = mockk<MemoSynchronizer>(relaxed = true)
                val repository =
                    MemoMutationRepositoryImpl(
                        memoPinDao = mockk<MemoPinDao>(),
                        synchronizer = synchronizer,
                        reminderScheduler = FakeReminderCoordinator(),
                        memoQueryRepository = mockk<MemoQueryRepository>(),
                        writeAuthority =
                            WorkspaceWriteAuthority(
                                engineReadinessRepository = FakeEngineReadinessRepository(
                                EngineReadiness.ReadOnlyRecovery(
                                category = EngineReadiness.FailureCategory.PERMISSION,
                                code = "saf_grant_revoked",
                                retryDisposition = EngineReadiness.RetryDisposition.AFTER_USER_ACTION,
                                diagnostic = "revoked",
                                ),
                                ),
                                writeFreezeRepository = ProcessWriteFreezeRepository(),
                            ),
                    )

                val error =
                    shouldThrow<IllegalStateException> {
                        repository.saveMemo("blocked", 1L, null)
                    }
                error.message.shouldContain("read-only recovery")
                coVerify(exactly = 0) { synchronizer.saveMemo(any(), any(), any()) }
            }
        }

        test("given Ready engine when saveMemo runs then synchronizer is used") {
            runTest {
                val saved =
                    Memo(
                        id = "id-1",
                        timestamp = 1L,
                        content = "ok",
                        rawContent = "ok",
                        dateKey = "2026_07_17",
                    )
                val synchronizer = mockk<MemoSynchronizer>()
                coEvery { synchronizer.saveMemo("ok", 1L, null) } returns saved
                val repository =
                    MemoMutationRepositoryImpl(
                        memoPinDao = mockk<MemoPinDao>(),
                        synchronizer = synchronizer,
                        reminderScheduler = FakeReminderCoordinator(),
                        memoQueryRepository = mockk<MemoQueryRepository>(),
                        writeAuthority =
                            WorkspaceWriteAuthority(
                                engineReadinessRepository = FakeEngineReadinessRepository(),
                                writeFreezeRepository = ProcessWriteFreezeRepository(),
                            ),
                    )

                repository.saveMemo("ok", 1L, null) shouldBe saved
                coVerify(exactly = 1) { synchronizer.saveMemo("ok", 1L, null) }
            }
        }

        test("given write freeze when saveMemo runs then write fails closed") {
            runTest {
                val freeze = ProcessWriteFreezeRepository()
                freeze.begin()
                val synchronizer = mockk<MemoSynchronizer>(relaxed = true)
                val repository =
                    MemoMutationRepositoryImpl(
                        memoPinDao = mockk<MemoPinDao>(),
                        synchronizer = synchronizer,
                        reminderScheduler = FakeReminderCoordinator(),
                        memoQueryRepository = mockk<MemoQueryRepository>(),
                        writeAuthority =
                            WorkspaceWriteAuthority(
                                engineReadinessRepository = FakeEngineReadinessRepository(),
                                writeFreezeRepository = freeze,
                            ),
                    )

                val error =
                    shouldThrow<IllegalStateException> {
                        repository.saveMemo("blocked", 1L, null)
                    }
                error.message.shouldContain("switch is in progress")
                coVerify(exactly = 0) { synchronizer.saveMemo(any(), any(), any()) }
            }
        }
    }
}
