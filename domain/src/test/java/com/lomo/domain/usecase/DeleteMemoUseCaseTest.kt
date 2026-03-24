package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: DeleteMemoUseCase
 * - Behavior focus: delete requests go through the shared memo-maintenance policy.
 * - Observable outcomes: delegated delete call with the selected memo.
 * - Excludes: repository deletion internals and trash/file synchronization behavior.
 */
class DeleteMemoUseCaseTest {
    private val memoMaintenanceUseCase: MemoMaintenanceUseCase = mockk(relaxed = true)
    private val useCase = DeleteMemoUseCase(memoMaintenanceUseCase)

    @Test
    fun `invoke delegates delete to maintenance use case`() =
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
