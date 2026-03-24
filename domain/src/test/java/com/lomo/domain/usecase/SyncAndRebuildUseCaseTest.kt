package com.lomo.domain.usecase

import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.SyncPolicyRepository
import com.lomo.domain.repository.WebDavSyncRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: SyncAndRebuildUseCase
 * - Behavior focus: force vs best-effort sync, refresh ordering, and cancellation or error propagation.
 * - Observable outcomes: thrown exception type, refresh execution, and collaborator call ordering.
 * - Excludes: repository internals, transport implementation details, and UI rendering.
 */
class SyncAndRebuildUseCaseTest {
    private val memoRepository: MemoRepository = mockk()
    private val gitSyncRepository: GitSyncRepository = mockk()
    private val webDavSyncRepository: WebDavSyncRepository = mockk()
    private val syncPolicyRepository: SyncPolicyRepository = mockk()
    private val useCase =
        SyncAndRebuildUseCase(
            memoRepository = memoRepository,
            gitSyncRepository = gitSyncRepository,
            webDavSyncRepository = webDavSyncRepository,
            syncPolicyRepository = syncPolicyRepository,
        )

    @Test
    fun `non-force git sync cancellation is rethrown and refresh is skipped`() =
        runTest {
            every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.GIT)
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
    fun `force sync failure still refreshes and rethrows original git error`() =
        runTest {
            every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.GIT)
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
    fun `force sync webdav result error still refreshes and throws mapped failure`() =
        runTest {
            every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.WEBDAV)
            coEvery { webDavSyncRepository.sync() } returns WebDavSyncResult.Error("sync failed")
            coEvery { memoRepository.refreshMemos() } returns Unit

            val thrown = runCatching { useCase(forceSync = true) }.exceptionOrNull()
            assertTrue(thrown is Exception)
            assertEquals("sync failed", thrown?.message)

            coVerifyOrder {
                webDavSyncRepository.sync()
                memoRepository.refreshMemos()
            }
        }

    @Test
    fun `force sync result cancellation is rethrown for webdav`() =
        runTest {
            every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.WEBDAV)
            val cancellation = CancellationException("cancelled")
            coEvery {
                webDavSyncRepository.sync()
            } returns WebDavSyncResult.Error("cancelled", cancellation)
            coEvery { memoRepository.refreshMemos() } returns Unit

            try {
                useCase(forceSync = true)
                fail("Expected CancellationException")
            } catch (e: CancellationException) {
                assertSame(cancellation, e)
            }

            coVerify(exactly = 1) { webDavSyncRepository.sync() }
            coVerify(exactly = 0) { memoRepository.refreshMemos() }
        }

    @Test
    fun `non-force sync failure remains best-effort and refresh still runs`() =
        runTest {
            every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.GIT)
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

    @Test
    fun `non-force webdav refresh skips remote sync when disabled`() =
        runTest {
            every { syncPolicyRepository.observeRemoteSyncBackend() } returns flowOf(SyncBackendType.WEBDAV)
            every { webDavSyncRepository.getSyncOnRefreshEnabled() } returns flowOf(true)
            every { webDavSyncRepository.isWebDavSyncEnabled() } returns flowOf(false)
            coEvery { memoRepository.refreshMemos() } returns Unit

            useCase(forceSync = false)

            coVerify(exactly = 0) { webDavSyncRepository.sync() }
            coVerify(exactly = 1) { memoRepository.refreshMemos() }
        }
}
