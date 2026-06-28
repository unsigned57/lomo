package com.lomo.app.feature.settings

import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.fakes.FakeCredentialRepository
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.GitSyncStatus
import com.lomo.domain.model.StoredCredentialStatus
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.MemoMutationRepository
import com.lomo.domain.repository.SyncPolicyRepository
import com.lomo.domain.usecase.GitRemoteUrlUseCase
import com.lomo.domain.usecase.GitSyncSettingsUseCase
import com.lomo.domain.usecase.SyncAndRebuildUseCase
import com.lomo.domain.usecase.SyncProviderRegistry
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: SettingsGitCoordinator credential status state
 * - Owning layer: app/settings
 * - Priority tier: P1
 * - Capability: keep typed credential read failures observable in settings instead of flattening them to booleans.
 *
 * Scenarios:
 * - Given a Git token read returns Unreadable, when settings refreshes credential state, then the coordinator exposes Unreadable and the legacy configured flag remains false.
 *
 * Observable outcomes:
 * - Settings coordinator StateFlow values for typed token status and derived configured compatibility.
 *
 * TDD proof:
 * - RED: targeted app test fails to compile before the repository/usecase/coordinator expose typed credential status.
 *
 * Excludes:
 * - Compose rendering, Android Keystore I/O, Git transport, and other settings sections.
 *
 * Test Change Justification:
 * - Reason category: App layer restructuring replaced page-based memo retention and viewport delete animations with LomoList system, extracted provider settings dialogs, and added conflict/startup orchestration.
 * - Old behavior/assertion being replaced: previous app-layer tests relied on monolithic settings dialogs, DeleteViewportEntry animation system, and pre-LomoList memo retention.
 * - Why old assertion is no longer correct: the app layer was restructured: settings dialogs are now provider-specific, DeleteViewportEntry files are removed in favor of LomoList components, and paged memo content uses new pagination source.
 * - Coverage preserved by: all existing scenarios retained; assertions updated to use new LomoList animation contracts, provider settings surfaces, and paging source APIs.
 * - Why this is not fitting the test to the implementation: tests verify observable ViewModel state, UI coordinator behavior, and screen rendering outcomes, not internal animation or dialog mechanics.
 */
class SettingsCredentialStatusStateTest : AppFunSpec() {
    init {
        test("given git token unreadable when settings refreshes credential state then unreadable remains observable") {
            runTest {
                val repository = FakeGitCredentialStatusRepository(StoredCredentialStatus.Unreadable)
                val credentialRepository =
                    FakeCredentialRepository().apply {
                        setFieldStatus(CredentialField.GIT_TOKEN, StoredCredentialStatus.Unreadable)
                    }
                val coordinator =
                    SettingsGitCoordinator(
                        gitSyncSettingsUseCase =
                            GitSyncSettingsUseCase(
                                gitSyncRepository = repository,
                                syncPolicyRepository = NoOpSyncPolicyRepository(),
                                syncAndRebuildUseCase =
                                    SyncAndRebuildUseCase(
                                        memoRepository = NoOpMemoMutationRepository(),
                                        syncProviderRegistry = SyncProviderRegistry(emptySet()),
                                        syncPolicyRepository = NoOpSyncPolicyRepository(),
                                    ),
                                gitRemoteUrlUseCase = GitRemoteUrlUseCase(),
                            ),
                        credentialCoordinator = SettingsCredentialCoordinator(credentialRepository),
                        scope = backgroundScope,
                    )

                coordinator.refreshPatConfigured()

                coordinator.gitPatStatus.value shouldBe StoredCredentialStatus.Unreadable
                coordinator.gitPatConfigured.value shouldBe false
            }
        }
    }
}

private class FakeGitCredentialStatusRepository(
    private val tokenStatus: StoredCredentialStatus,
) : GitSyncRepository {
    override fun isGitSyncEnabled(): Flow<Boolean> = flowOf(false)

    override fun getRemoteUrl(): Flow<String?> = flowOf(null)

    override fun getAutoSyncEnabled(): Flow<Boolean> = flowOf(false)

    override fun getAutoSyncInterval(): Flow<String> = flowOf("30m")

    override fun observeLastSyncTimeMillis(): Flow<Long?> = flowOf(null)

    override fun getSyncOnRefreshEnabled(): Flow<Boolean> = flowOf(false)

    override suspend fun setGitSyncEnabled(enabled: Boolean) = Unit

    override suspend fun setRemoteUrl(url: String) = Unit

    override suspend fun setToken(token: String) = Unit

    override suspend fun getTokenStatus(): StoredCredentialStatus = tokenStatus

    override suspend fun setAuthorInfo(
        name: String,
        email: String,
    ) = Unit

    override fun getAuthorName(): Flow<String> = flowOf("")

    override fun getAuthorEmail(): Flow<String> = flowOf("")

    override suspend fun setAutoSyncEnabled(enabled: Boolean) = Unit

    override suspend fun setAutoSyncInterval(interval: String) = Unit

    override suspend fun setSyncOnRefreshEnabled(enabled: Boolean) = Unit

    override suspend fun initOrClone(): GitSyncResult = GitSyncResult.Success("")

    override suspend fun sync(): GitSyncResult = GitSyncResult.Success("")

    override suspend fun getStatus(): GitSyncStatus = GitSyncStatus(false, 0, 0, null)

    override suspend fun testConnection(): GitSyncResult = GitSyncResult.Success("")

    override suspend fun resetRepository(): GitSyncResult = GitSyncResult.Success("")

    override suspend fun resetLocalBranchToRemote(): GitSyncResult = GitSyncResult.Success("")

    override suspend fun forcePushLocalToRemote(): GitSyncResult = GitSyncResult.Success("")

    override suspend fun resolveConflicts(
        resolution: SyncConflictResolution,
        conflictSet: SyncConflictSet,
    ): GitSyncResult = GitSyncResult.Success("")

    override fun syncState(): Flow<UnifiedSyncState> = flowOf(UnifiedSyncState.Idle)
}

private class NoOpSyncPolicyRepository : SyncPolicyRepository {
    override fun ensureCoreSyncActive() = Unit

    override fun observeRemoteSyncBackend(): Flow<SyncBackendType> = flowOf(SyncBackendType.NONE)

    override suspend fun setRemoteSyncBackend(type: SyncBackendType) = Unit

    override suspend fun applyRemoteSyncPolicy() = Unit
}

private class NoOpMemoMutationRepository : MemoMutationRepository {
    override suspend fun refreshMemos() = Unit

    override suspend fun saveMemo(
        content: String,
        timestamp: Long,
        geoLocation: String?,
    ) = Unit

    override suspend fun updateMemo(
        memo: com.lomo.domain.model.Memo,
        newContent: String,
    ) = Unit

    override suspend fun deleteMemo(memo: com.lomo.domain.model.Memo) = Unit

    override suspend fun restoreMemoRevision(
        currentMemo: com.lomo.domain.model.Memo,
        revisionId: String,
    ) = Unit

    override suspend fun setMemoPinned(
        memoId: String,
        pinned: Boolean,
    ) = Unit
}
