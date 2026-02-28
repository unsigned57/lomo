package com.lomo.domain.usecase

import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.MemoRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class SyncAndRebuildUseCaseTest {
    private val memoRepository: MemoRepository = mockk()
    private val gitSyncRepository: GitSyncRepository = mockk()
    private val useCase = SyncAndRebuildUseCase(memoRepository, gitSyncRepository)

    @Test
    fun `non-force sync cancellation is rethrown and refresh is skipped`() =
        runTest {
            every { gitSyncRepository.getSyncOnRefreshEnabled() } returns flowOf(true)
            every { gitSyncRepository.isGitSyncEnabled() } returns flowOf(true)
            val cancellation = CancellationException("cancelled")
            coEvery { gitSyncRepository.sync() } throws cancellation

            try {
                useCase(forceSync = false)
                fail("Expected CancellationException")
            } catch (e: CancellationException) {
                assertSame(cancellation, e)
            }

            coVerify(exactly = 1) { gitSyncRepository.sync() }
            coVerify(exactly = 0) { memoRepository.refreshMemos() }
        }

    @Test
    fun `force sync failure still refreshes and rethrows original error`() =
        runTest {
            val failure = IllegalStateException("sync failed")
            coEvery { gitSyncRepository.sync() } throws failure
            coEvery { memoRepository.refreshMemos() } returns Unit

            try {
                useCase(forceSync = true)
                fail("Expected IllegalStateException")
            } catch (e: IllegalStateException) {
                assertSame(failure, e)
            }

            coVerifyOrder {
                gitSyncRepository.sync()
                memoRepository.refreshMemos()
            }
        }

    @Test
    fun `non-force sync failure remains best-effort and refresh still runs`() =
        runTest {
            every { gitSyncRepository.getSyncOnRefreshEnabled() } returns flowOf(true)
            every { gitSyncRepository.isGitSyncEnabled() } returns flowOf(true)
            coEvery { gitSyncRepository.sync() } throws IllegalArgumentException("sync failed")
            coEvery { memoRepository.refreshMemos() } returns Unit

            useCase(forceSync = false)

            coVerifyOrder {
                gitSyncRepository.sync()
                memoRepository.refreshMemos()
            }
        }
}
