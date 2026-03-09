package com.lomo.domain.usecase

import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncEngineState
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.SyncPolicyRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

class GitSyncSettingsUseCase
    constructor(
        private val gitSyncRepository: GitSyncRepository,
        private val syncPolicyRepository: SyncPolicyRepository,
        private val syncAndRebuildUseCase: SyncAndRebuildUseCase,
        private val gitRemoteUrlUseCase: GitRemoteUrlUseCase,
    ) {
        fun observeGitSyncEnabled(): Flow<Boolean> = gitSyncRepository.isGitSyncEnabled()

        fun observeRemoteUrl(): Flow<String?> = gitSyncRepository.getRemoteUrl()

        fun observeAuthorName(): Flow<String> = gitSyncRepository.getAuthorName()

        fun observeAuthorEmail(): Flow<String> = gitSyncRepository.getAuthorEmail()

        fun observeAutoSyncEnabled(): Flow<Boolean> = gitSyncRepository.getAutoSyncEnabled()

        fun observeAutoSyncInterval(): Flow<String> = gitSyncRepository.getAutoSyncInterval()

        fun observeSyncOnRefreshEnabled(): Flow<Boolean> = gitSyncRepository.getSyncOnRefreshEnabled()

        fun observeLastSyncTimeMillis(): Flow<Long?> = gitSyncRepository.observeLastSyncTimeMillis()

        fun observeSyncState(): Flow<SyncEngineState> = gitSyncRepository.syncState()

        suspend fun isTokenConfigured(): Boolean = gitSyncRepository.getToken() != null

        suspend fun updateGitSyncEnabled(enabled: Boolean) {
            syncPolicyRepository.setRemoteSyncBackend(if (enabled) SyncBackendType.GIT else SyncBackendType.NONE)
            syncPolicyRepository.applyRemoteSyncPolicy()
        }

        fun isValidRemoteUrl(url: String): Boolean = gitRemoteUrlUseCase.isValid(url)

        suspend fun updateRemoteUrl(url: String) {
            gitSyncRepository.setRemoteUrl(gitRemoteUrlUseCase.normalize(url))
        }

        suspend fun updateToken(token: String) {
            gitSyncRepository.setToken(token)
        }

        suspend fun updateAuthorInfo(
            name: String,
            email: String,
        ) {
            gitSyncRepository.setAuthorInfo(name, email)
        }

        suspend fun updateAutoSyncEnabled(enabled: Boolean) {
            gitSyncRepository.setAutoSyncEnabled(enabled)
            syncPolicyRepository.applyRemoteSyncPolicy()
        }

        suspend fun updateAutoSyncInterval(interval: String) {
            gitSyncRepository.setAutoSyncInterval(interval)
            syncPolicyRepository.applyRemoteSyncPolicy()
        }

        suspend fun updateSyncOnRefreshEnabled(enabled: Boolean) {
            gitSyncRepository.setSyncOnRefreshEnabled(enabled)
        }

        suspend fun triggerSyncNow() {
            syncAndRebuildUseCase(forceSync = true)
        }

        suspend fun resolveConflictUsingRemote(): GitSyncResult =
            runGitOperation {
                when (val result = gitSyncRepository.resetLocalBranchToRemote()) {
                    is GitSyncResult.Error -> {
                        result
                    }

                    else -> {
                        syncAndRebuildUseCase(forceSync = false)
                        result
                    }
                }
            }

        suspend fun resolveConflictUsingLocal(): GitSyncResult =
            runGitOperation {
                when (val result = gitSyncRepository.forcePushLocalToRemote()) {
                    is GitSyncResult.Error -> {
                        result
                    }

                    else -> {
                        syncAndRebuildUseCase(forceSync = false)
                        result
                    }
                }
            }

        suspend fun testConnection(): GitSyncResult = gitSyncRepository.testConnection()

        suspend fun resetRepository(): GitSyncResult = gitSyncRepository.resetRepository()

        private suspend fun runGitOperation(block: suspend () -> GitSyncResult): GitSyncResult =
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                GitSyncResult.Error(
                    message = throwable.message.orEmpty(),
                    exception = throwable,
                )
            }
    }
