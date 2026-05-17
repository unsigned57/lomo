/*
 * Test Contract:
 * - Unit under test: MemoMaintenanceUseCaseTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for MemoMaintenanceUseCaseTest.
 * - Boundary: boundary and edge cases for MemoMaintenanceUseCaseTest.
 * - Failure: failure and error scenarios for MemoMaintenanceUseCaseTest.
 * - Must-not-happen: invariants are never violated for MemoMaintenanceUseCaseTest.
 *
 * - Behavior focus: test behavioral outcomes of MemoMaintenanceUseCaseTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.testing.DomainFunSpec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
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
                        val repository: MemoRepository = mockk()
                        val syncAndRebuildUseCase: SyncAndRebuildUseCase = mockk()
                        val useCase = MemoMaintenanceUseCase(repository, syncAndRebuildUseCase)
                        coEvery { repository.deleteMemo(memo) } returns Unit
                        coEvery { syncAndRebuildUseCase.invoke(false) } returns Unit

                        useCase.deleteMemo(memo)
                        useCase.refreshMemos()

                        coVerify(exactly = 1) { repository.deleteMemo(memo) }
                        coVerify(exactly = 1) { syncAndRebuildUseCase.invoke(false) }
                    }
        }
    }
    init {
        test("repository-only constructor keeps refresh as no-op") {
            runTest {
                        val repository: MemoRepository = mockk()
                        val useCase = MemoMaintenanceUseCase(repository)
                        coEvery { repository.deleteMemo(memo) } returns Unit

                        useCase.refreshMemos()
                        coVerify(exactly = 0) { repository.deleteMemo(any()) }

                        useCase.deleteMemo(memo)
                        coVerify(exactly = 1) { repository.deleteMemo(memo) }
                    }
        }
    }
    init {
        test("sync-only constructor keeps delete as no-op and refresh delegates") {
            runTest {
                        val syncAndRebuildUseCase: SyncAndRebuildUseCase = mockk()
                        val useCase = MemoMaintenanceUseCase(syncAndRebuildUseCase)
                        coEvery { syncAndRebuildUseCase.invoke(false) } returns Unit

                        useCase.deleteMemo(memo)
                        coVerify(exactly = 0) { syncAndRebuildUseCase.invoke(any()) }

                        useCase.refreshMemos()
                        coVerify(exactly = 1) { syncAndRebuildUseCase.invoke(false) }
                    }
        }
    }
}
