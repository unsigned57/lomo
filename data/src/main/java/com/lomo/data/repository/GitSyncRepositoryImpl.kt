package com.lomo.data.repository

import com.lomo.data.git.GitCredentialStore
import com.lomo.data.git.GitSyncEngine
import com.lomo.data.git.SafGitMirrorBridge
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.GitSyncState
import com.lomo.domain.model.GitSyncStatus
import com.lomo.domain.repository.GitSyncRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.io.File
import javax.inject.Inject

class GitSyncRepositoryImpl
    @Inject
    constructor(
        private val gitSyncEngine: GitSyncEngine,
        private val credentialStore: GitCredentialStore,
        private val dataStore: LomoDataStore,
        private val memoSynchronizer: MemoSynchronizer,
        private val safGitMirrorBridge: SafGitMirrorBridge,
    ) : GitSyncRepository {
        companion object {
            private const val MSG_PAT_REQUIRED = "No Personal Access Token configured"
        }

        override fun isGitSyncEnabled(): Flow<Boolean> = dataStore.gitSyncEnabled

        override fun getRemoteUrl(): Flow<String?> = dataStore.gitRemoteUrl

        override fun getAutoSyncEnabled(): Flow<Boolean> = dataStore.gitAutoSyncEnabled

        override fun getAutoSyncInterval(): Flow<String> = dataStore.gitAutoSyncInterval

        override fun getLastSyncTime(): Flow<Long> = dataStore.gitLastSyncTime

        override suspend fun setGitSyncEnabled(enabled: Boolean) {
            dataStore.updateGitSyncEnabled(enabled)
        }

        override suspend fun setRemoteUrl(url: String) {
            dataStore.updateGitRemoteUrl(url)
        }

        override suspend fun setToken(token: String) {
            credentialStore.setToken(token)
        }

        override suspend fun getToken(): String? = credentialStore.getToken()

        override suspend fun setAuthorInfo(name: String, email: String) {
            dataStore.updateGitAuthorName(name)
            dataStore.updateGitAuthorEmail(email)
        }

        override fun getAuthorName(): Flow<String> = dataStore.gitAuthorName

        override fun getAuthorEmail(): Flow<String> = dataStore.gitAuthorEmail

        override suspend fun setAutoSyncEnabled(enabled: Boolean) {
            dataStore.updateGitAutoSyncEnabled(enabled)
        }

        override suspend fun setAutoSyncInterval(interval: String) {
            dataStore.updateGitAutoSyncInterval(interval)
        }

        override suspend fun initOrClone(): GitSyncResult {
            val remoteUrl = dataStore.gitRemoteUrl.first()
            if (remoteUrl.isNullOrBlank()) {
                gitSyncEngine.markNotConfigured()
                return GitSyncResult.NotConfigured
            }
            if (credentialStore.getToken().isNullOrBlank()) {
                gitSyncEngine.markError(MSG_PAT_REQUIRED)
                return GitSyncResult.Error(MSG_PAT_REQUIRED)
            }

            val directRootDir = resolveRootDir()
            if (directRootDir != null) {
                return gitSyncEngine.initOrClone(directRootDir, remoteUrl)
            }

            val safRootUri = resolveSafRootUri()
            if (!safRootUri.isNullOrBlank()) {
                return runCatching {
                    val mirrorDir = safGitMirrorBridge.mirrorDirectoryFor(safRootUri)
                    safGitMirrorBridge.pullFromSaf(safRootUri, mirrorDir)

                    val result = gitSyncEngine.initOrClone(mirrorDir, remoteUrl)
                    if (result is GitSyncResult.Success) {
                        safGitMirrorBridge.pushToSaf(safRootUri, mirrorDir)
                    }
                    result
                }.getOrElse { e ->
                    val message = e.message ?: "Failed to initialize SAF mirror for git sync"
                    gitSyncEngine.markError(message)
                    GitSyncResult.Error(message, e)
                }
            }

            gitSyncEngine.markError("Memo directory is not configured")
            return GitSyncResult.DirectPathRequired
        }

        override suspend fun sync(): GitSyncResult {
            val enabled = dataStore.gitSyncEnabled.first()
            if (!enabled) {
                gitSyncEngine.markNotConfigured()
                return GitSyncResult.NotConfigured
            }

            val remoteUrl = dataStore.gitRemoteUrl.first()
            if (remoteUrl.isNullOrBlank()) {
                gitSyncEngine.markNotConfigured()
                return GitSyncResult.NotConfigured
            }
            if (credentialStore.getToken().isNullOrBlank()) {
                gitSyncEngine.markError(MSG_PAT_REQUIRED)
                return GitSyncResult.Error(MSG_PAT_REQUIRED)
            }

            val directRootDir = resolveRootDir()
            val safRootUri = resolveSafRootUri()
            if (directRootDir == null && safRootUri.isNullOrBlank()) {
                gitSyncEngine.markError("Memo directory is not configured")
                return GitSyncResult.DirectPathRequired
            }

            val rootDir =
                if (directRootDir != null) {
                    directRootDir
                } else {
                    try {
                        val mirrorDir = safGitMirrorBridge.mirrorDirectoryFor(safRootUri!!)
                        safGitMirrorBridge.pullFromSaf(safRootUri, mirrorDir)
                        mirrorDir
                    } catch (e: Exception) {
                        val message = e.message ?: "Failed to prepare SAF mirror for git sync"
                        gitSyncEngine.markError(message)
                        return GitSyncResult.Error(message, e)
                    }
                }

            // Initialize if needed
            val gitDir = File(rootDir, ".git")
            if (!gitDir.exists()) {
                val initResult = gitSyncEngine.initOrClone(rootDir, remoteUrl)
                if (initResult is GitSyncResult.Error) return initResult
            }

            val result = gitSyncEngine.sync(rootDir)

            if (result is GitSyncResult.Success && !safRootUri.isNullOrBlank()) {
                try {
                    safGitMirrorBridge.pushToSaf(safRootUri, rootDir)
                } catch (e: Exception) {
                    val message = e.message ?: "Failed to write synced files back to SAF storage"
                    gitSyncEngine.markError(message)
                    return GitSyncResult.Error(message, e)
                }
            }

            // Refresh Room DB after sync to pick up any new/changed files from remote
            if (result is GitSyncResult.Success) {
                try {
                    memoSynchronizer.refresh()
                } catch (e: Exception) {
                    Timber.w(e, "Memo refresh after git sync failed")
                }
            }

            return result
        }

        override suspend fun getStatus(): GitSyncStatus {
            val rootDir =
                resolveRootDir()
                    ?: run {
                        val safRootUri = resolveSafRootUri()
                        if (safRootUri.isNullOrBlank()) {
                            return GitSyncStatus(
                                hasLocalChanges = false,
                                aheadCount = 0,
                                behindCount = 0,
                                lastSyncTime = null,
                            )
                        }
                        runCatching {
                            val mirrorDir = safGitMirrorBridge.mirrorDirectoryFor(safRootUri)
                            safGitMirrorBridge.pullFromSaf(safRootUri, mirrorDir)
                            mirrorDir
                        }.getOrElse {
                            return GitSyncStatus(
                                hasLocalChanges = false,
                                aheadCount = 0,
                                behindCount = 0,
                                lastSyncTime = null,
                            )
                        }
                    }

            val status = gitSyncEngine.getStatus(rootDir)
            val lastSync = dataStore.gitLastSyncTime.first()
            return status.copy(lastSyncTime = if (lastSync > 0) lastSync else null)
        }

        override fun syncState(): Flow<GitSyncState> = gitSyncEngine.syncState

        private suspend fun resolveRootDir(): File? {
            // Git sync prefers direct filesystem path when available.
            val directPath = dataStore.rootDirectory.first()
            if (!directPath.isNullOrBlank()) {
                val dir = File(directPath)
                if (dir.exists() && dir.isDirectory) return dir
            }
            return null
        }

        private suspend fun resolveSafRootUri(): String? {
            val rootUri = dataStore.rootUri.first()
            return rootUri?.takeIf { it.isNotBlank() }
        }
    }
