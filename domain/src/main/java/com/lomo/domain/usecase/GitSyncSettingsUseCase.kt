package com.lomo.domain.usecase

import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.SyncPolicyRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

interface GitSyncSettingsStateObservation {
    fun observeGitSyncEnabled(): Flow<Boolean>

    fun observeRemoteUrl(): Flow<String?>

    fun observeAuthorName(): Flow<String>

    fun observeAuthorEmail(): Flow<String>

    fun observeAutoSyncEnabled(): Flow<Boolean>

    fun observeAutoSyncInterval(): Flow<String>

    fun observeSyncOnRefreshEnabled(): Flow<Boolean>

    fun observeLastSyncTimeMillis(): Flow<Long?>

    fun observeSyncState(): Flow<UnifiedSyncState>
}

interface GitSyncSettingsValidation {
    suspend fun isTokenConfigured(): Boolean

    fun isValidRemoteUrl(url: String): Boolean
}

interface GitSyncSettingsMutation {
    suspend fun updateGitSyncEnabled(enabled: Boolean)

    suspend fun updateRemoteUrl(url: String)

    suspend fun updateToken(token: String)

    suspend fun updateAuthorInfo(
        name: String,
        email: String,
    )

    suspend fun updateAutoSyncEnabled(enabled: Boolean)

    suspend fun updateAutoSyncInterval(interval: String)

    suspend fun updateSyncOnRefreshEnabled(enabled: Boolean)
}

interface GitSyncSettingsActions {
    suspend fun triggerSyncNow()

    suspend fun resolveConflictUsingRemote(): GitSyncResult

    suspend fun resolveConflictUsingLocal(): GitSyncResult

    suspend fun testConnection(): GitSyncResult

    suspend fun resetRepository(): GitSyncResult
}

class GitSyncSettingsUseCase
(
        gitSyncRepository: GitSyncRepository,
        syncPolicyRepository: SyncPolicyRepository,
        syncAndRebuildUseCase: SyncAndRebuildUseCase,
        gitRemoteUrlUseCase: GitRemoteUrlUseCase,
    ) : GitSyncSettingsStateObservation by
        GitSyncSettingsStateObservationImpl(
            gitSyncRepository = gitSyncRepository,
        ),
    GitSyncSettingsValidation by
        GitSyncSettingsValidationImpl(
            gitSyncRepository = gitSyncRepository,
            gitRemoteUrlUseCase = gitRemoteUrlUseCase,
        ),
    GitSyncSettingsMutation by
        GitSyncSettingsMutationImpl(
            gitSyncRepository = gitSyncRepository,
            syncPolicyRepository = syncPolicyRepository,
            gitRemoteUrlUseCase = gitRemoteUrlUseCase,
        ),
    GitSyncSettingsActions by
        GitSyncSettingsActionsImpl(
            gitSyncRepository = gitSyncRepository,
            syncAndRebuildUseCase = syncAndRebuildUseCase,
        )

private class GitSyncSettingsStateObservationImpl(
    private val gitSyncRepository: GitSyncRepository,
) : GitSyncSettingsStateObservation {
    private val shared =
        RemoteSyncSharedStateObservationImpl(
            enabled = gitSyncRepository::isGitSyncEnabled,
            autoSyncEnabled = gitSyncRepository::getAutoSyncEnabled,
            autoSyncInterval = gitSyncRepository::getAutoSyncInterval,
            syncOnRefreshEnabled = gitSyncRepository::getSyncOnRefreshEnabled,
            lastSyncTimeMillis = gitSyncRepository::observeLastSyncTimeMillis,
            syncState = gitSyncRepository::syncState,
        )

    override fun observeGitSyncEnabled(): Flow<Boolean> = shared.observeSyncEnabled()

    override fun observeRemoteUrl(): Flow<String?> = gitSyncRepository.getRemoteUrl()

    override fun observeAuthorName(): Flow<String> = gitSyncRepository.getAuthorName()

    override fun observeAuthorEmail(): Flow<String> = gitSyncRepository.getAuthorEmail()

    override fun observeAutoSyncEnabled(): Flow<Boolean> = shared.observeAutoSyncEnabled()

    override fun observeAutoSyncInterval(): Flow<String> = shared.observeAutoSyncInterval()

    override fun observeSyncOnRefreshEnabled(): Flow<Boolean> = shared.observeSyncOnRefreshEnabled()

    override fun observeLastSyncTimeMillis(): Flow<Long?> = shared.observeLastSyncTimeMillis()

    override fun observeSyncState(): Flow<UnifiedSyncState> = shared.observeSyncState()
}

private class GitSyncSettingsValidationImpl(
    private val gitSyncRepository: GitSyncRepository,
    private val gitRemoteUrlUseCase: GitRemoteUrlUseCase,
) : GitSyncSettingsValidation {
    override suspend fun isTokenConfigured(): Boolean = gitSyncRepository.getToken() != null

    override fun isValidRemoteUrl(url: String): Boolean = gitRemoteUrlUseCase.isValid(url)
}

private class GitSyncSettingsMutationImpl(
    private val gitSyncRepository: GitSyncRepository,
    private val syncPolicyRepository: SyncPolicyRepository,
    private val gitRemoteUrlUseCase: GitRemoteUrlUseCase,
) : GitSyncSettingsMutation {
    private val shared =
        RemoteSyncSharedMutationImpl(
            backendType = SyncBackendType.GIT,
            syncPolicyRepository = syncPolicyRepository,
            autoSyncEnabledUpdater = gitSyncRepository::setAutoSyncEnabled,
            autoSyncIntervalUpdater = gitSyncRepository::setAutoSyncInterval,
            syncOnRefreshUpdater = gitSyncRepository::setSyncOnRefreshEnabled,
        )

    override suspend fun updateGitSyncEnabled(enabled: Boolean) {
        shared.updateSyncEnabled(enabled)
    }

    override suspend fun updateRemoteUrl(url: String) {
        gitSyncRepository.setRemoteUrl(gitRemoteUrlUseCase.normalize(url))
    }

    override suspend fun updateToken(token: String) {
        gitSyncRepository.setToken(token)
    }

    override suspend fun updateAuthorInfo(
        name: String,
        email: String,
    ) {
        gitSyncRepository.setAuthorInfo(name, email)
    }

    override suspend fun updateAutoSyncEnabled(enabled: Boolean) {
        shared.updateAutoSyncEnabled(enabled)
    }

    override suspend fun updateAutoSyncInterval(interval: String) {
        shared.updateAutoSyncInterval(interval)
    }

    override suspend fun updateSyncOnRefreshEnabled(enabled: Boolean) {
        shared.updateSyncOnRefreshEnabled(enabled)
    }
}

private class GitSyncSettingsActionsImpl(
    private val gitSyncRepository: GitSyncRepository,
    private val syncAndRebuildUseCase: SyncAndRebuildUseCase,
) : GitSyncSettingsActions {
    private val shared =
        RemoteSyncSharedActionsImpl(
            syncAndRebuildUseCase = syncAndRebuildUseCase,
            connectionTester = gitSyncRepository::testConnection,
        )

    override suspend fun triggerSyncNow() {
        shared.triggerSyncNow()
    }

    override suspend fun resolveConflictUsingRemote(): GitSyncResult =
        runGitOperation {
            when (val result = gitSyncRepository.resetLocalBranchToRemote()) {
                is GitSyncResult.Error -> result
                else -> {
                    syncAndRebuildUseCase(forceSync = false)
                    result
                }
            }
        }

    override suspend fun resolveConflictUsingLocal(): GitSyncResult =
        runGitOperation {
            when (val result = gitSyncRepository.forcePushLocalToRemote()) {
                is GitSyncResult.Error -> result
                else -> {
                    syncAndRebuildUseCase(forceSync = false)
                    result
                }
            }
        }

    override suspend fun testConnection(): GitSyncResult = shared.testConnection()

    override suspend fun resetRepository(): GitSyncResult = gitSyncRepository.resetRepository()

    private suspend fun runGitOperation(block: suspend () -> GitSyncResult): GitSyncResult =
        runCatching { block() }
            .getOrElse { throwable ->
                if (throwable is CancellationException) {
                    throw throwable
                }
                GitSyncResult.Error(
                    message = throwable.message.orEmpty(),
                    exception = throwable,
                )
            }
}
