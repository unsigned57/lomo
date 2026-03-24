package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: MemoMaintenanceUseCase
 * - Behavior focus: constructor compatibility and delegated delete or refresh behavior.
 * - Observable outcomes: repository delete delegation, sync trigger with force=false, and no-op compatibility paths.
 * - Excludes: repository implementation internals, sync engine details, and UI rendering.
 */
class MemoMaintenanceUseCaseTest {
    private val memo =
        Memo(
            id = "memo-1",
            timestamp = 1L,
            content = "content",
            rawContent = "- 10:00 content",
            dateKey = "2026_03_24",
        )

    @Test
    fun `repository plus sync constructor delegates delete and refresh`() =
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

    @Test
    fun `repository-only constructor keeps refresh as no-op`() =
        runTest {
            val repository: MemoRepository = mockk()
            val useCase = MemoMaintenanceUseCase(repository)
            coEvery { repository.deleteMemo(memo) } returns Unit

            useCase.refreshMemos()
            coVerify(exactly = 0) { repository.deleteMemo(any()) }

            useCase.deleteMemo(memo)
            coVerify(exactly = 1) { repository.deleteMemo(memo) }
        }

    @Test
    fun `sync-only constructor keeps delete as no-op and refresh delegates`() =
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
