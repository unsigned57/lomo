package com.lomo.domain.usecase

import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.SyncPolicyRepository
import io.kotest.assertions.fail
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: GitSyncSettingsUseCase
 * - Behavior focus: remote backend policy updates, conflict-resolution actions, and exception mapping semantics.
 * - Observable outcomes: backend policy writes, delegated repository calls, sync follow-up invocation ordering, and returned result mapping.
 * - Red phase: Fails before behavior changes or migration are applied.
 * - Excludes: repository implementation details, network behavior, and UI rendering.
 */
class GitSyncSettingsUseCaseTest : DomainFunSpec() {
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
    init {
        test("updateGitSyncEnabled true applies Git backend policy") {
            runTest {
                        coEvery { syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.GIT) } returns Unit
                        coEvery { syncPolicyRepository.applyRemoteSyncPolicy() } returns Unit

                        useCase.updateGitSyncEnabled(enabled = true)

                        coVerifyOrder {
                            syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.GIT)
                            syncPolicyRepository.applyRemoteSyncPolicy()
                        }
                    }
        }
    }
    init {
        test("updateGitSyncEnabled false applies None backend policy") {
            runTest {
                        coEvery { syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.NONE) } returns Unit
                        coEvery { syncPolicyRepository.applyRemoteSyncPolicy() } returns Unit

                        useCase.updateGitSyncEnabled(enabled = false)

                        coVerifyOrder {
                            syncPolicyRepository.setRemoteSyncBackend(SyncBackendType.NONE)
                            syncPolicyRepository.applyRemoteSyncPolicy()
                        }
                    }
        }
    }
    init {
        test("updateRemoteUrl normalizes then saves url") {
            runTest {
                        every { gitRemoteUrlUseCase.normalize(" https://example.com/org/repo.git/ ") } returns "https://example.com/org/repo.git"
                        coEvery { gitSyncRepository.setRemoteUrl("https://example.com/org/repo.git") } returns Unit

                        useCase.updateRemoteUrl(" https://example.com/org/repo.git/ ")

                        verify(exactly = 1) { gitRemoteUrlUseCase.normalize(" https://example.com/org/repo.git/ ") }
                        coVerify(exactly = 1) { gitSyncRepository.setRemoteUrl("https://example.com/org/repo.git") }
                    }
        }
    }
    init {
        test("isValidRemoteUrl delegates to url policy") {
            every { gitRemoteUrlUseCase.isValid("https://example.com/org/repo.git") } returns true

            val result = useCase.isValidRemoteUrl("https://example.com/org/repo.git")

            (result) shouldBe true
            verify(exactly = 1) { gitRemoteUrlUseCase.isValid("https://example.com/org/repo.git") }
        }
    }
    init {
        test("updateAutoSyncEnabled writes flag and reapplies policy") {
            runTest {
                        coEvery { gitSyncRepository.setAutoSyncEnabled(true) } returns Unit
                        coEvery { syncPolicyRepository.applyRemoteSyncPolicy() } returns Unit

                        useCase.updateAutoSyncEnabled(enabled = true)

                        coVerifyOrder {
                            gitSyncRepository.setAutoSyncEnabled(true)
                            syncPolicyRepository.applyRemoteSyncPolicy()
                        }
                    }
        }
    }
    init {
        test("updateAutoSyncInterval writes interval and reapplies policy") {
            runTest {
                        coEvery { gitSyncRepository.setAutoSyncInterval("15m") } returns Unit
                        coEvery { syncPolicyRepository.applyRemoteSyncPolicy() } returns Unit

                        useCase.updateAutoSyncInterval(interval = "15m")

                        coVerifyOrder {
                            gitSyncRepository.setAutoSyncInterval("15m")
                            syncPolicyRepository.applyRemoteSyncPolicy()
                        }
                    }
        }
    }
    init {
        test("updateSyncOnRefreshEnabled only writes repository flag") {
            runTest {
                        coEvery { gitSyncRepository.setSyncOnRefreshEnabled(true) } returns Unit

                        useCase.updateSyncOnRefreshEnabled(enabled = true)

                        coVerify(exactly = 1) { gitSyncRepository.setSyncOnRefreshEnabled(true) }
                        coVerify(exactly = 0) { syncPolicyRepository.applyRemoteSyncPolicy() }
                    }
        }
    }
    init {
        test("triggerSyncNow delegates with forceSync true") {
            runTest {
                        coEvery { syncAndRebuildUseCase.invoke(forceSync = true) } returns Unit

                        useCase.triggerSyncNow()

                        coVerify(exactly = 1) { syncAndRebuildUseCase.invoke(forceSync = true) }
                    }
        }
    }
    init {
        test("resolveConflictUsingRemote success triggers follow-up refresh sync") {
            runTest {
                        val success = GitSyncResult.Success("remote reset")
                        coEvery { gitSyncRepository.resetLocalBranchToRemote() } returns success
                        coEvery { syncAndRebuildUseCase.invoke(forceSync = false) } returns Unit

                        val result = useCase.resolveConflictUsingRemote()

                        result shouldBe success
                        coVerifyOrder {
                            gitSyncRepository.resetLocalBranchToRemote()
                            syncAndRebuildUseCase.invoke(forceSync = false)
                        }
                    }
        }
    }
    init {
        test("resolveConflictUsingRemote error result skips follow-up refresh sync") {
            runTest {
                        val failure = GitSyncResult.Error("conflict unresolved")
                        coEvery { gitSyncRepository.resetLocalBranchToRemote() } returns failure

                        val result = useCase.resolveConflictUsingRemote()

                        result shouldBe failure
                        coVerify(exactly = 0) { syncAndRebuildUseCase.invoke(forceSync = false) }
                    }
        }
    }
    init {
        test("resolveConflictUsingLocal non-cancellation exception maps to error result") {
            runTest {
                        val failure = IllegalStateException("push failed")
                        coEvery { gitSyncRepository.forcePushLocalToRemote() } throws failure

                        val result = useCase.resolveConflictUsingLocal()

                        val error = result.shouldBeInstanceOf<GitSyncResult.Error>()
                        error.message shouldBe "push failed"
                        error.exception shouldBe failure
                        coVerify(exactly = 0) { syncAndRebuildUseCase.invoke(forceSync = false) }
                    }
        }
    }
    init {
        test("resolveConflictUsingLocal cancellation is rethrown") {
            runTest {
                        val cancellation = CancellationException("cancelled")
                        coEvery { gitSyncRepository.forcePushLocalToRemote() } throws cancellation

                        try {
                            useCase.resolveConflictUsingLocal()
                            fail("Expected CancellationException")
                        } catch (e: CancellationException) {
                            e shouldBe cancellation
                        }
                    }
        }
    }
    init {
        test("testConnection and resetRepository delegate to git repository") {
            runTest {
                        val testResult = GitSyncResult.Success("ok")
                        val resetResult = GitSyncResult.Success("reset")
                        coEvery { gitSyncRepository.testConnection() } returns testResult
                        coEvery { gitSyncRepository.resetRepository() } returns resetResult

                        useCase.testConnection() shouldBe testResult
                        useCase.resetRepository() shouldBe resetResult

                        coVerify(exactly = 1) { gitSyncRepository.testConnection() }
                        coVerify(exactly = 1) { gitSyncRepository.resetRepository() }
                    }
        }
    }
    init {
        test("state observation delegates expose repository flows") {
            runTest {
                        every { gitSyncRepository.isGitSyncEnabled() } returns flowOf(true)
                        every { gitSyncRepository.getRemoteUrl() } returns flowOf("https://example.com/repo.git")
                        every { gitSyncRepository.getAuthorName() } returns flowOf("Lomo")
                        every { gitSyncRepository.getAuthorEmail() } returns flowOf("lomo@example.com")
                        every { gitSyncRepository.getAutoSyncEnabled() } returns flowOf(true)
                        every { gitSyncRepository.getAutoSyncInterval() } returns flowOf("30m")
                        every { gitSyncRepository.getSyncOnRefreshEnabled() } returns flowOf(true)
                        every { gitSyncRepository.observeLastSyncTimeMillis() } returns flowOf(1234L)
                        every { gitSyncRepository.syncState() } returns
                            flowOf(
                                com.lomo.domain.model.UnifiedSyncState.Running(
                                    provider = com.lomo.domain.model.SyncBackendType.GIT,
                                    phase = com.lomo.domain.model.UnifiedSyncPhase.PULLING,
                                ),
                            )

                        useCase.observeGitSyncEnabled().first() shouldBe true
                        useCase.observeRemoteUrl().first() shouldBe "https://example.com/repo.git"
                        useCase.observeAuthorName().first() shouldBe "Lomo"
                        useCase.observeAuthorEmail().first() shouldBe "lomo@example.com"
                        useCase.observeAutoSyncEnabled().first() shouldBe true
                        useCase.observeAutoSyncInterval().first() shouldBe "30m"
                        useCase.observeSyncOnRefreshEnabled().first() shouldBe true
                        useCase.observeLastSyncTimeMillis().first() shouldBe 1234L
                        useCase.observeSyncState().first() shouldBe com.lomo.domain.model.UnifiedSyncState.Running(
                                provider = com.lomo.domain.model.SyncBackendType.GIT,
                                phase = com.lomo.domain.model.UnifiedSyncPhase.PULLING,
                            )
                    }
        }
    }
}
