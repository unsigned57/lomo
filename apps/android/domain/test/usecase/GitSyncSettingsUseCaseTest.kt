package com.lomo.domain.usecase

import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.UnifiedSyncPhase
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeGitSyncRepository
import com.lomo.domain.testing.fakes.FakeMemoMutationRepository
import com.lomo.domain.testing.fakes.FakeMemoStore
import com.lomo.domain.testing.fakes.FakeSyncPolicyRepository
import io.kotest.assertions.fail
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class GitSyncSettingsUseCaseTest : DomainFunSpec() {
    private val eventLog = mutableListOf<String>()
    private lateinit var gitSyncRepository: FakeGitSyncRepository
    private lateinit var syncPolicyRepository: FakeSyncPolicyRepository
    private lateinit var useCase: GitSyncSettingsUseCase

    init {
        beforeTest {
            eventLog.clear()
            gitSyncRepository = FakeGitSyncRepository()
            syncPolicyRepository = FakeSyncPolicyRepository(eventLog = eventLog)
            useCase =
                GitSyncSettingsUseCase(
                    gitSyncRepository = gitSyncRepository,
                    syncPolicyRepository = syncPolicyRepository,
                    syncAndRebuildUseCase =
                    SyncAndRebuildUseCase(
                        memoRepository = FakeMemoMutationRepository(FakeMemoStore()),
                        syncProviderRegistry = SyncProviderRegistry(emptySet()),
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

        test("branch observation and mutation persist an explicit configured branch") {
            runTest {
                gitSyncRepository.setBranchValue("master")

                useCase.observeBranch().first() shouldBe "master"
                useCase.isValidBranch("release-2") shouldBe true

                useCase.updateBranch(" release-2 ")

                gitSyncRepository.branchWrites shouldBe listOf("release-2")
                useCase.observeBranch().first() shouldBe "release-2"
            }
        }

        test("updateBranch rejects invalid branch names") {
            runTest {
                listOf("", "feature/x", "wip.lock", "a..b", "-wip").forEach { invalid ->
                    try {
                        useCase.updateBranch(invalid)
                        fail("Expected rejection for branch=$invalid")
                    } catch (e: IllegalArgumentException) {
                        // expected
                    }
                }
                gitSyncRepository.branchWrites shouldBe emptyList()
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
