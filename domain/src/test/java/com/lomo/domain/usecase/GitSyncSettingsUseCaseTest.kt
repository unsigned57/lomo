package com.lomo.domain.usecase

import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.SyncPolicyRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: GitSyncSettingsUseCase
 * - Behavior focus: remote backend policy updates, conflict-resolution actions, and exception mapping semantics.
 * - Observable outcomes: backend policy writes, delegated repository calls, sync follow-up invocation ordering, and returned result mapping.
 * - Excludes: repository implementation details, network behavior, and UI rendering.
 */
class GitSyncSettingsUseCaseTest {
    private val gitSyncRepository: GitSyncRepository = mockk(relaxed = true)
    private val syncPolicyRepository: SyncPolicyRepository = mockk(relaxed = true)
    private val syncAndRebuildUseCase: SyncAndRebuildUseCase = mockk(relaxed = true)
    private val gitRemoteUrlUseCase: GitRemoteUrlUseCase = mockk(relaxed = true)

    private val useCase =
        GitSyncSettingsUseCase(
            gitSyncRepository = gitSyncRepository,
            syncPolicyRepository = syncPolicyRepository,
            syncAndRebuildUseCase = syncAndRebuildUseCase,
            gitRemoteUrlUseCase = gitRemoteUrlUseCase,
        )

    @Test
    fun `updateGitSyncEnabled true applies Git backend policy`() =
        runTest {
            coEvery { syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.GIT) } returns Unit
            coEvery { syncPolicyRepository.applyRemoteSyncPolicy() } returns Unit

            useCase.updateGitSyncEnabled(enabled = true)

            coVerifyOrder {
                syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.GIT)
                syncPolicyRepository.applyRemoteSyncPolicy()
            }
        }

    @Test
    fun `updateGitSyncEnabled false applies None backend policy`() =
        runTest {
            coEvery { syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.NONE) } returns Unit
            coEvery { syncPolicyRepository.applyRemoteSyncPolicy() } returns Unit

            useCase.updateGitSyncEnabled(enabled = false)

            coVerifyOrder {
                syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.NONE)
                syncPolicyRepository.applyRemoteSyncPolicy()
            }
        }

    @Test
    fun `updateRemoteUrl normalizes then saves url`() =
        runTest {
            every { gitRemoteUrlUseCase.normalize(" https://example.com/org/repo.git/ ") } returns "https://example.com/org/repo.git"
            coEvery { gitSyncRepository.setRemoteUrl("https://example.com/org/repo.git") } returns Unit

            useCase.updateRemoteUrl(" https://example.com/org/repo.git/ ")

            verify(exactly = 1) { gitRemoteUrlUseCase.normalize(" https://example.com/org/repo.git/ ") }
            coVerify(exactly = 1) { gitSyncRepository.setRemoteUrl("https://example.com/org/repo.git") }
        }

    @Test
    fun `isValidRemoteUrl delegates to url policy`() {
        every { gitRemoteUrlUseCase.isValid("https://example.com/org/repo.git") } returns true

        val result = useCase.isValidRemoteUrl("https://example.com/org/repo.git")

        assertTrue(result)
        verify(exactly = 1) { gitRemoteUrlUseCase.isValid("https://example.com/org/repo.git") }
    }

    @Test
    fun `updateAutoSyncEnabled writes flag and reapplies policy`() =
        runTest {
            coEvery { gitSyncRepository.setAutoSyncEnabled(true) } returns Unit
            coEvery { syncPolicyRepository.applyRemoteSyncPolicy() } returns Unit

            useCase.updateAutoSyncEnabled(enabled = true)

            coVerifyOrder {
                gitSyncRepository.setAutoSyncEnabled(true)
                syncPolicyRepository.applyRemoteSyncPolicy()
            }
        }

    @Test
    fun `updateAutoSyncInterval writes interval and reapplies policy`() =
        runTest {
            coEvery { gitSyncRepository.setAutoSyncInterval("15m") } returns Unit
            coEvery { syncPolicyRepository.applyRemoteSyncPolicy() } returns Unit

            useCase.updateAutoSyncInterval(interval = "15m")

            coVerifyOrder {
                gitSyncRepository.setAutoSyncInterval("15m")
                syncPolicyRepository.applyRemoteSyncPolicy()
            }
        }

    @Test
    fun `updateSyncOnRefreshEnabled only writes repository flag`() =
        runTest {
            coEvery { gitSyncRepository.setSyncOnRefreshEnabled(true) } returns Unit

            useCase.updateSyncOnRefreshEnabled(enabled = true)

            coVerify(exactly = 1) { gitSyncRepository.setSyncOnRefreshEnabled(true) }
            coVerify(exactly = 0) { syncPolicyRepository.applyRemoteSyncPolicy() }
        }

    @Test
    fun `triggerSyncNow delegates with forceSync true`() =
        runTest {
            coEvery { syncAndRebuildUseCase.invoke(forceSync = true) } returns Unit

            useCase.triggerSyncNow()

            coVerify(exactly = 1) { syncAndRebuildUseCase.invoke(forceSync = true) }
        }

    @Test
    fun `resolveConflictUsingRemote success triggers follow-up refresh sync`() =
        runTest {
            val success = GitSyncResult.Success("remote reset")
            coEvery { gitSyncRepository.resetLocalBranchToRemote() } returns success
            coEvery { syncAndRebuildUseCase.invoke(forceSync = false) } returns Unit

            val result = useCase.resolveConflictUsingRemote()

            assertEquals(success, result)
            coVerifyOrder {
                gitSyncRepository.resetLocalBranchToRemote()
                syncAndRebuildUseCase.invoke(forceSync = false)
            }
        }

    @Test
    fun `resolveConflictUsingRemote error result skips follow-up refresh sync`() =
        runTest {
            val failure = GitSyncResult.Error("conflict unresolved")
            coEvery { gitSyncRepository.resetLocalBranchToRemote() } returns failure

            val result = useCase.resolveConflictUsingRemote()

            assertEquals(failure, result)
            coVerify(exactly = 0) { syncAndRebuildUseCase.invoke(forceSync = false) }
        }

    @Test
    fun `resolveConflictUsingLocal non-cancellation exception maps to error result`() =
        runTest {
            val failure = IllegalStateException("push failed")
            coEvery { gitSyncRepository.forcePushLocalToRemote() } throws failure

            val result = useCase.resolveConflictUsingLocal()

            assertTrue(result is GitSyncResult.Error)
            val error = result as GitSyncResult.Error
            assertEquals("push failed", error.message)
            assertSame(failure, error.exception)
            coVerify(exactly = 0) { syncAndRebuildUseCase.invoke(forceSync = false) }
        }

    @Test
    fun `resolveConflictUsingLocal cancellation is rethrown`() =
        runTest {
            val cancellation = CancellationException("cancelled")
            coEvery { gitSyncRepository.forcePushLocalToRemote() } throws cancellation

            try {
                useCase.resolveConflictUsingLocal()
                fail("Expected CancellationException")
            } catch (e: CancellationException) {
                assertSame(cancellation, e)
            }
        }

    @Test
    fun `testConnection and resetRepository delegate to git repository`() =
        runTest {
            val testResult = GitSyncResult.Success("ok")
            val resetResult = GitSyncResult.Success("reset")
            coEvery { gitSyncRepository.testConnection() } returns testResult
            coEvery { gitSyncRepository.resetRepository() } returns resetResult

            assertEquals(testResult, useCase.testConnection())
            assertEquals(resetResult, useCase.resetRepository())

            coVerify(exactly = 1) { gitSyncRepository.testConnection() }
            coVerify(exactly = 1) { gitSyncRepository.resetRepository() }
        }
}
