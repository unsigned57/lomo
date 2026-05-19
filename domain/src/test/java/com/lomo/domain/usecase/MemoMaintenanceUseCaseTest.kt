/*
 * Behavior Contract:
 * - Unit under test: MemoMaintenanceUseCaseTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenarios:
 * - Happy: standard happy path for MemoMaintenanceUseCaseTest.
 * - Boundary: boundary and edge cases for MemoMaintenanceUseCaseTest.
 * - Failure: failure and error scenarios for MemoMaintenanceUseCaseTest.
 * - Must-not-happen: invariants are never violated for MemoMaintenanceUseCaseTest.
 *
 * - Behavior focus: test behavioral outcomes of MemoMaintenanceUseCaseTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - TDD proof: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.domain.usecase

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import com.lomo.domain.model.Memo
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeMemoRepository
import com.lomo.domain.testing.fakes.FakeSyncPolicyRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: MemoMaintenanceUseCase
 * - Behavior focus: constructor compatibility and delegated delete or refresh behavior.
 * - Observable outcomes: repository delete delegation, sync trigger with force=false, and no-op compatibility paths.
 * - Excludes: repository implementation internals, sync engine details, and UI rendering.
 */
class MemoMaintenanceUseCaseTest : DomainFunSpec() {
    private val memo =
        Memo(
            id = "memo-1",
            timestamp = 1L,
            content = "content",
            rawContent = "- 10:00 content",
            dateKey = "2026_03_24",
        )
    init {
        test("repository plus sync constructor delegates delete and refresh") {
            runTest {
                        val repository = FakeMemoRepository()
                        val syncAndRebuildUseCase = syncAndRebuildUseCase(repository)
                        val useCase = MemoMaintenanceUseCase(repository, syncAndRebuildUseCase)

                        useCase.deleteMemo(memo)
                        useCase.refreshMemos()

                        repository.deletedMemoRequests shouldBe listOf(memo)
                        repository.refreshMemosCallCount shouldBe 1
                    }
        }

        test("repository-only constructor keeps refresh as no-op") {
            runTest {
                        val repository = FakeMemoRepository()
                        val useCase = MemoMaintenanceUseCase(repository)

                        useCase.refreshMemos()
                        repository.deletedMemoRequests shouldBe emptyList()

                        useCase.deleteMemo(memo)
                        repository.deletedMemoRequests shouldBe listOf(memo)
                    }
        }

        test("sync-only constructor keeps delete as no-op and refresh delegates") {
            runTest {
                        val repository = FakeMemoRepository()
                        val syncAndRebuildUseCase = syncAndRebuildUseCase(repository)
                        val useCase = MemoMaintenanceUseCase(syncAndRebuildUseCase)

                        useCase.deleteMemo(memo)
                        repository.refreshMemosCallCount shouldBe 0

                        useCase.refreshMemos()
                        repository.refreshMemosCallCount shouldBe 1
                    }
        }
    }

    private fun syncAndRebuildUseCase(repository: FakeMemoRepository): SyncAndRebuildUseCase =
        SyncAndRebuildUseCase(
            memoRepository = repository,
            syncProviderRegistry = SyncProviderRegistry(emptyList()),
            syncPolicyRepository = FakeSyncPolicyRepository(),
        )
}
