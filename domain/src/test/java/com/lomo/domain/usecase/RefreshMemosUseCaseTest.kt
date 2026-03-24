package com.lomo.domain.usecase

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: RefreshMemosUseCase
 * - Behavior focus: refresh requests route through memo-maintenance orchestration.
 * - Observable outcomes: delegated refresh invocation.
 * - Excludes: sync engine behavior, refresh ordering, and repository internals already covered elsewhere.
 */
class RefreshMemosUseCaseTest {
    private val memoMaintenanceUseCase: MemoMaintenanceUseCase = mockk(relaxed = true)
    private val useCase = RefreshMemosUseCase(memoMaintenanceUseCase)

    @Test
    fun `invoke delegates refresh to maintenance use case`() =
        runTest {
            coEvery { memoMaintenanceUseCase.refreshMemos() } returns Unit

            useCase()

            coVerify(exactly = 1) { memoMaintenanceUseCase.refreshMemos() }
        }
}
