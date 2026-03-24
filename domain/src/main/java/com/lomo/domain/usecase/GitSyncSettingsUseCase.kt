package com.lomo.domain.usecase

import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncEngineState
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

    fun observeSyncState(): Flow<SyncEngineState>
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
    override fun observeGitSyncEnabled(): Flow<Boolean> = gitSyncRepository.isGitSyncEnabled()

    override fun observeRemoteUrl(): Flow<String?> = gitSyncRepository.getRemoteUrl()

    override fun observeAuthorName(): Flow<String> = gitSyncRepository.getAuthorName()

    override fun observeAuthorEmail(): Flow<String> = gitSyncRepository.getAuthorEmail()

    override fun observeAutoSyncEnabled(): Flow<Boolean> = gitSyncRepository.getAutoSyncEnabled()

    override fun observeAutoSyncInterval(): Flow<String> = gitSyncRepository.getAutoSyncInterval()

    override fun observeSyncOnRefreshEnabled(): Flow<Boolean> = gitSyncRepository.getSyncOnRefreshEnabled()

    override fun observeLastSyncTimeMillis(): Flow<Long?> = gitSyncRepository.observeLastSyncTimeMillis()

    override fun observeSyncState(): Flow<SyncEngineState> = gitSyncRepository.syncState()
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
    override suspend fun updateGitSyncEnabled(enabled: Boolean) {
        syncPolicyRepository.setRemoteSyncBackend(if (enabled) SyncBackendType.GIT else SyncBackendType.NONE)
        syncPolicyRepository.applyRemoteSyncPolicy()
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
        gitSyncRepository.setAutoSyncEnabled(enabled)
        syncPolicyRepository.applyRemoteSyncPolicy()
    }

    override suspend fun updateAutoSyncInterval(interval: String) {
        gitSyncRepository.setAutoSyncInterval(interval)
        syncPolicyRepository.applyRemoteSyncPolicy()
    }

    override suspend fun updateSyncOnRefreshEnabled(enabled: Boolean) {
        gitSyncRepository.setSyncOnRefreshEnabled(enabled)
    }
}

private class GitSyncSettingsActionsImpl(
    private val gitSyncRepository: GitSyncRepository,
    private val syncAndRebuildUseCase: SyncAndRebuildUseCase,
) : GitSyncSettingsActions {
    override suspend fun triggerSyncNow() {
        syncAndRebuildUseCase(forceSync = true)
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

    override suspend fun testConnection(): GitSyncResult = gitSyncRepository.testConnection()

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
