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


import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.UnifiedSyncPhase
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeGitSyncRepository
import com.lomo.domain.testing.fakes.FakeMemoRepository
import com.lomo.domain.testing.fakes.FakeSyncPolicyRepository
import io.kotest.assertions.fail
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: GitSyncSettingsUseCase
 * - Behavior focus: remote backend policy updates, Git settings mutation, conflict-resolution actions, and exception mapping semantics.
 * - Observable outcomes: backend policy writes, fake repository state, memo refresh count, and returned result mapping.
 * - TDD proof: Fails before behavior changes or migration are applied.
 * - Excludes: repository implementation details, network behavior, and UI rendering.
 */
class GitSyncSettingsUseCaseTest : DomainFunSpec() {
    private val eventLog = mutableListOf<String>()
    private lateinit var gitSyncRepository: FakeGitSyncRepository
    private lateinit var syncPolicyRepository: FakeSyncPolicyRepository
    private lateinit var memoRepository: FakeMemoRepository
    private lateinit var useCase: GitSyncSettingsUseCase

    init {
        beforeTest {
            eventLog.clear()
            gitSyncRepository = FakeGitSyncRepository()
            syncPolicyRepository = FakeSyncPolicyRepository(eventLog = eventLog)
            memoRepository = FakeMemoRepository()
            useCase =
                GitSyncSettingsUseCase(
                    gitSyncRepository = gitSyncRepository,
                    syncPolicyRepository = syncPolicyRepository,
                    syncAndRebuildUseCase =
                        SyncAndRebuildUseCase(
                            memoRepository = memoRepository,
                            syncProviderRegistry = SyncProviderRegistry(emptyList()),
                            syncPolicyRepository = syncPolicyRepository,
                        ),
                    gitRemoteUrlUseCase = GitRemoteUrlUseCase(),
                )
        }

        test("updateGitSyncEnabled applies backend policy") {
            runTest {
                useCase.updateGitSyncEnabled(enabled = true)
                useCase.updateGitSyncEnabled(enabled = false)

                syncPolicyRepository.setBackendRequests shouldBe
                    listOf(SyncBackendType.GIT, SyncBackendType.NONE)
                syncPolicyRepository.applyRemoteSyncPolicyCallCount shouldBe 2
                eventLog shouldBe
                    listOf(
                        "syncPolicy.setRemoteSyncBackend:GIT",
                        "syncPolicy.applyRemoteSyncPolicy",
                        "syncPolicy.setRemoteSyncBackend:NONE",
                        "syncPolicy.applyRemoteSyncPolicy",
                    )
            }
        }

        test("git setting mutations update fake repository state") {
            runTest {
                useCase.updateRemoteUrl(" https://example.com/org/repo.git/ ")
                useCase.updateToken("token")
                useCase.updateAuthorInfo(name = "Lomo", email = "lomo@example.com")

                gitSyncRepository.remoteUrlWrites shouldBe listOf("https://example.com/org/repo.git")
                gitSyncRepository.tokenWrites shouldBe listOf("token")
                gitSyncRepository.authorInfoWrites shouldBe listOf("Lomo" to "lomo@example.com")
                useCase.isTokenConfigured() shouldBe true
                useCase.isValidRemoteUrl("https://example.com/org/repo.git") shouldBe true
            }
        }

        test("auto sync mutations reapply policy while sync-on-refresh only writes flag") {
            runTest {
                useCase.updateAutoSyncEnabled(enabled = true)
                useCase.updateAutoSyncInterval(interval = "15m")
                useCase.updateSyncOnRefreshEnabled(enabled = true)

                gitSyncRepository.autoSyncEnabledWrites shouldBe listOf(true)
                gitSyncRepository.autoSyncIntervalWrites shouldBe listOf("15m")
                gitSyncRepository.syncOnRefreshEnabledWrites shouldBe listOf(true)
                syncPolicyRepository.applyRemoteSyncPolicyCallCount shouldBe 2
            }
        }

        test("triggerSyncNow forces a memo refresh through shared actions") {
            runTest {
                useCase.triggerSyncNow()

                memoRepository.refreshMemosCallCount shouldBe 1
            }
        }

        test("resolveConflictUsingRemote success triggers follow-up refresh sync") {
            runTest {
                val success = GitSyncResult.Success("remote reset")
                gitSyncRepository.nextResetLocalBranchToRemoteResult = success

                val result = useCase.resolveConflictUsingRemote()

                result shouldBe success
                gitSyncRepository.resetLocalBranchToRemoteCallCount shouldBe 1
                memoRepository.refreshMemosCallCount shouldBe 1
            }
        }

        test("resolveConflictUsingRemote error result skips follow-up refresh sync") {
            runTest {
                val failure = GitSyncResult.Error("conflict unresolved")
                gitSyncRepository.nextResetLocalBranchToRemoteResult = failure

                val result = useCase.resolveConflictUsingRemote()

                result shouldBe failure
                gitSyncRepository.resetLocalBranchToRemoteCallCount shouldBe 1
                memoRepository.refreshMemosCallCount shouldBe 0
            }
        }

        test("resolveConflictUsingLocal non-cancellation exception maps to error result") {
            runTest {
                val failure = IllegalStateException("push failed")
                gitSyncRepository.forcePushLocalFailure = failure

                val result = useCase.resolveConflictUsingLocal()

                val error = result.shouldBeInstanceOf<GitSyncResult.Error>()
                error.message shouldBe "push failed"
                error.exception shouldBe failure
                memoRepository.refreshMemosCallCount shouldBe 0
            }
        }

        test("resolveConflictUsingLocal cancellation is rethrown") {
            runTest {
                val cancellation = CancellationException("cancelled")
                gitSyncRepository.forcePushLocalFailure = cancellation

                try {
                    useCase.resolveConflictUsingLocal()
                    fail("Expected CancellationException")
                } catch (e: CancellationException) {
                    e shouldBe cancellation
                }
            }
        }

        test("testConnection and resetRepository delegate to git repository") {
            runTest {
                val testResult = GitSyncResult.Success("ok")
                val resetResult = GitSyncResult.Success("reset")
                gitSyncRepository.nextTestConnectionResult = testResult
                gitSyncRepository.nextResetRepositoryResult = resetResult

                useCase.testConnection() shouldBe testResult
                useCase.resetRepository() shouldBe resetResult
                gitSyncRepository.testConnectionCallCount shouldBe 1
                gitSyncRepository.resetRepositoryCallCount shouldBe 1
            }
        }

        test("state observation exposes repository flows") {
            runTest {
                val expectedState =
                    UnifiedSyncState.Running(
                        provider = SyncBackendType.GIT,
                        phase = UnifiedSyncPhase.PULLING,
                    )
                gitSyncRepository.setEnabled(true)
                gitSyncRepository.setRemoteUrlValue("https://example.com/repo.git")
                gitSyncRepository.setAuthorNameValue("Lomo")
                gitSyncRepository.setAuthorEmailValue("lomo@example.com")
                gitSyncRepository.setAutoSyncEnabledValue(true)
                gitSyncRepository.setAutoSyncIntervalValue("30m")
                gitSyncRepository.setSyncOnRefreshEnabledValue(true)
                gitSyncRepository.setLastSyncTimeMillis(1234L)
                gitSyncRepository.setSyncState(expectedState)

                useCase.observeGitSyncEnabled().first() shouldBe true
                useCase.observeRemoteUrl().first() shouldBe "https://example.com/repo.git"
                useCase.observeAuthorName().first() shouldBe "Lomo"
                useCase.observeAuthorEmail().first() shouldBe "lomo@example.com"
                useCase.observeAutoSyncEnabled().first() shouldBe true
                useCase.observeAutoSyncInterval().first() shouldBe "30m"
                useCase.observeSyncOnRefreshEnabled().first() shouldBe true
                useCase.observeLastSyncTimeMillis().first() shouldBe 1234L
                useCase.observeSyncState().first() shouldBe expectedState
            }
        }
    }
}
