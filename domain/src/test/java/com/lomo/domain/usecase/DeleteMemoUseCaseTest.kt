/*
 * Test Contract:
 * - Unit under test: DeleteMemoUseCaseTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for DeleteMemoUseCaseTest.
 * - Boundary: boundary and edge cases for DeleteMemoUseCaseTest.
 * - Failure: failure and error scenarios for DeleteMemoUseCaseTest.
 * - Must-not-happen: invariants are never violated for DeleteMemoUseCaseTest.
 *
 * - Behavior focus: test behavioral outcomes of DeleteMemoUseCaseTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.testing.DomainFunSpec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: DeleteMemoUseCase
 * - Behavior focus: delete requests go through the shared memo-maintenance policy.
 * - Observable outcomes: delegated delete call with the selected memo.
 * - Excludes: repository deletion internals and trash/file synchronization behavior.
 */
class DeleteMemoUseCaseTest : DomainFunSpec() {
    private val memoMaintenanceUseCase: MemoMaintenanceUseCase = mockk(relaxed = true)
    private val useCase = DeleteMemoUseCase(memoMaintenanceUseCase)
    init {
        test("invoke delegates delete to maintenance use case") {
            runTest {
                        val memo =
                            Memo(
                                id = "memo-1",
                                timestamp = 123L,
                                content = "delete me",
                                rawContent = "- 10:00 delete me",
                                dateKey = "2026_03_24",
                            )
                        coEvery { memoMaintenanceUseCase.deleteMemo(memo) } returns Unit

                        useCase(memo)

                        coVerify(exactly = 1) { memoMaintenanceUseCase.deleteMemo(memo) }
                    }
        }
    }
}
