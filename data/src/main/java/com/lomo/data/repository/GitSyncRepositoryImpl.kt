package com.lomo.data.repository

import com.lomo.data.git.GitCredentialStore
import com.lomo.data.git.GitSyncErrorMessages
import com.lomo.data.git.GitSyncEngine
import com.lomo.data.git.SafGitMirrorBridge
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.parser.MarkdownParser
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.GitSyncStatus
import com.lomo.domain.model.MemoVersion
import com.lomo.domain.model.SyncEngineState
import com.lomo.domain.repository.GitSyncRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.math.abs

class GitSyncRepositoryImpl
    @Inject
    constructor(
        private val gitSyncEngine: GitSyncEngine,
        private val credentialStore: GitCredentialStore,
        private val dataStore: LomoDataStore,
        private val memoSynchronizer: MemoSynchronizer,
        private val safGitMirrorBridge: SafGitMirrorBridge,
        private val markdownParser: MarkdownParser,
    ) : GitSyncRepository {
        companion object {
            private const val MEMO_CHANGE_DEBOUNCE_MS = 5_000L
            private const val TIMESTAMP_TOLERANCE_MS = 1000L
        }

        private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val isSyncInProgress = AtomicBoolean(false)

        init {
            @OptIn(FlowPreview::class)
            syncScope.launch {
                memoSynchronizer.outboxDrainCompleted
                    .debounce(MEMO_CHANGE_DEBOUNCE_MS)
                    .collect {
                        try {
                            val enabled = dataStore.gitSyncEnabled.first()
                            if (enabled) {
                                commitLocal()
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "Event-driven git commit failed")
                        }
                    }
            }
        }

        override fun isGitSyncEnabled(): Flow<Boolean> = dataStore.gitSyncEnabled

        override fun getRemoteUrl(): Flow<String?> = dataStore.gitRemoteUrl

        override fun getAutoSyncEnabled(): Flow<Boolean> = dataStore.gitAutoSyncEnabled

        override fun getAutoSyncInterval(): Flow<String> = dataStore.gitAutoSyncInterval

        @Deprecated("Implements legacy sentinel-based API for compatibility.")
        override fun getLastSyncTime(): Flow<Long> = dataStore.gitLastSyncTime

        override fun getSyncOnRefreshEnabled(): Flow<Boolean> = dataStore.gitSyncOnRefresh

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

        override suspend fun setSyncOnRefreshEnabled(enabled: Boolean) {
            dataStore.updateGitSyncOnRefresh(enabled)
        }

        override suspend fun initOrClone(): GitSyncResult {
            val remoteUrl = dataStore.gitRemoteUrl.first()
            if (remoteUrl.isNullOrBlank()) {
                gitSyncEngine.markNotConfigured()
                return GitSyncResult.NotConfigured
            }
            if (credentialStore.getToken().isNullOrBlank()) {
                gitSyncEngine.markError(GitSyncErrorMessages.PAT_REQUIRED)
                return GitSyncResult.Error(GitSyncErrorMessages.PAT_REQUIRED)
            }

            val directRootDir = resolveRootDir()
            if (directRootDir != null) {
                return runGitIo {
                    gitSyncEngine.initOrClone(directRootDir, remoteUrl)
                }
            }

            val safRootUri = resolveSafRootUri()
            if (!safRootUri.isNullOrBlank()) {
                return runGitIo {
                    runCatching {
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
            }

            gitSyncEngine.markError("Memo directory is not configured")
            return GitSyncResult.DirectPathRequired
        }

        private suspend fun commitLocal(): GitSyncResult {
            if (!isSyncInProgress.compareAndSet(false, true)) {
                return GitSyncResult.Success("Sync in progress")
            }
            try {
                val enabled = dataStore.gitSyncEnabled.first()
                if (!enabled) return GitSyncResult.NotConfigured

                val directRootDir = resolveRootDir()
                    ?: return GitSyncResult.Success("SAF mode, skipping local commit")

                val gitDir = File(directRootDir, ".git")
                if (!gitDir.exists()) return GitSyncResult.Success("Not initialized yet")

                return runGitIo {
                    gitSyncEngine.commitLocal(directRootDir)
                }
            } finally {
                isSyncInProgress.set(false)
            }
        }

        override suspend fun sync(): GitSyncResult {
            if (!isSyncInProgress.compareAndSet(false, true)) {
                return GitSyncResult.Success("Sync already in progress")
            }
            try {
                return doSync()
            } finally {
                isSyncInProgress.set(false)
            }
        }

        private suspend fun doSync(): GitSyncResult {
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
                gitSyncEngine.markError(GitSyncErrorMessages.PAT_REQUIRED)
                return GitSyncResult.Error(GitSyncErrorMessages.PAT_REQUIRED)
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
                        runGitIo {
                            val mirrorDir = safGitMirrorBridge.mirrorDirectoryFor(safRootUri!!)
                            safGitMirrorBridge.pullFromSaf(safRootUri, mirrorDir)
                            mirrorDir
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val message = e.message ?: "Failed to prepare SAF mirror for git sync"
                        gitSyncEngine.markError(message)
                        return GitSyncResult.Error(message, e)
                    }
                }

            // Initialize if needed
            val gitDir = File(rootDir, ".git")
            if (!gitDir.exists()) {
                val initResult =
                    runGitIo {
                        gitSyncEngine.initOrClone(rootDir, remoteUrl)
                    }
                if (initResult is GitSyncResult.Error) return initResult
            }

            val result =
                runGitIo {
                    gitSyncEngine.sync(rootDir, remoteUrl)
                }

            if (result is GitSyncResult.Success && !safRootUri.isNullOrBlank()) {
                try {
                    runGitIo {
                        safGitMirrorBridge.pushToSaf(safRootUri, rootDir)
                    }
                } catch (e: CancellationException) {
                    throw e
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val causeMessage =
                        e.message
                            ?.takeIf { it.isNotBlank() }
                            ?: "unknown memo refresh error"
                    val message = "Git sync completed but memo refresh failed: $causeMessage"
                    gitSyncEngine.markError(message)
                    Timber.w(e, message)
                    return GitSyncResult.Error(message, e)
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
                        try {
                            runGitIo {
                                val mirrorDir = safGitMirrorBridge.mirrorDirectoryFor(safRootUri)
                                safGitMirrorBridge.pullFromSaf(safRootUri, mirrorDir)
                                mirrorDir
                            }
                        } catch (_: Exception) {
                            return GitSyncStatus(
                                hasLocalChanges = false,
                                aheadCount = 0,
                                behindCount = 0,
                                lastSyncTime = null,
                            )
                        }
                    }

            val status =
                runGitIo {
                    gitSyncEngine.getStatus(rootDir)
                }
            val lastSync = dataStore.gitLastSyncTime.first()
            return status.copy(lastSyncTime = if (lastSync > 0) lastSync else null)
        }

        override fun syncState(): Flow<SyncEngineState> = gitSyncEngine.syncState

        override suspend fun testConnection(): GitSyncResult {
            val remoteUrl = dataStore.gitRemoteUrl.first()
            if (remoteUrl.isNullOrBlank()) {
                return GitSyncResult.Error("Repository URL is not configured")
            }
            if (credentialStore.getToken().isNullOrBlank()) {
                return GitSyncResult.Error(GitSyncErrorMessages.PAT_REQUIRED)
            }
            return runGitIo {
                gitSyncEngine.testConnection(remoteUrl)
            }
        }

        override suspend fun resetRepository(): GitSyncResult {
            val directRootDir = resolveRootDir()
            if (directRootDir != null) {
                return runGitIo {
                    gitSyncEngine.resetRepository(directRootDir)
                }
            }
            val safRootUri = resolveSafRootUri()
            if (!safRootUri.isNullOrBlank()) {
                return try {
                    runGitIo {
                        val mirrorDir = safGitMirrorBridge.mirrorDirectoryFor(safRootUri)
                        gitSyncEngine.resetRepository(mirrorDir)
                    }
                } catch (e: Exception) {
                    GitSyncResult.Error("Reset failed: ${e.message}", e)
                }
            }
            return GitSyncResult.Error("Memo directory is not configured")
        }

        override suspend fun resetLocalBranchToRemote(): GitSyncResult {
            val remoteUrl = dataStore.gitRemoteUrl.first()
            if (remoteUrl.isNullOrBlank()) {
                return GitSyncResult.Error("Repository URL is not configured")
            }
            if (credentialStore.getToken().isNullOrBlank()) {
                return GitSyncResult.Error(GitSyncErrorMessages.PAT_REQUIRED)
            }

            val directRootDir = resolveRootDir()
            if (directRootDir != null) {
                return runGitIo {
                    gitSyncEngine.resetLocalBranchToRemote(directRootDir, remoteUrl)
                }
            }

            val safRootUri = resolveSafRootUri()
            if (!safRootUri.isNullOrBlank()) {
                return try {
                    runGitIo {
                        val mirrorDir = safGitMirrorBridge.mirrorDirectoryFor(safRootUri)
                        safGitMirrorBridge.pullFromSaf(safRootUri, mirrorDir)
                        val result = gitSyncEngine.resetLocalBranchToRemote(mirrorDir, remoteUrl)
                        if (result is GitSyncResult.Success) {
                            safGitMirrorBridge.pushToSaf(safRootUri, mirrorDir)
                        }
                        result
                    }
                } catch (e: Exception) {
                    GitSyncResult.Error("Reset to remote failed: ${e.message}", e)
                }
            }

            return GitSyncResult.Error("Memo directory is not configured")
        }

        override suspend fun forcePushLocalToRemote(): GitSyncResult {
            val remoteUrl = dataStore.gitRemoteUrl.first()
            if (remoteUrl.isNullOrBlank()) {
                return GitSyncResult.Error("Repository URL is not configured")
            }
            if (credentialStore.getToken().isNullOrBlank()) {
                return GitSyncResult.Error(GitSyncErrorMessages.PAT_REQUIRED)
            }

            val directRootDir = resolveRootDir()
            if (directRootDir != null) {
                return runGitIo {
                    gitSyncEngine.forcePushLocalToRemote(directRootDir, remoteUrl)
                }
            }

            val safRootUri = resolveSafRootUri()
            if (!safRootUri.isNullOrBlank()) {
                return try {
                    runGitIo {
                        val mirrorDir = safGitMirrorBridge.mirrorDirectoryFor(safRootUri)
                        safGitMirrorBridge.pullFromSaf(safRootUri, mirrorDir)
                        val result = gitSyncEngine.forcePushLocalToRemote(mirrorDir, remoteUrl)
                        if (result is GitSyncResult.Success) {
                            safGitMirrorBridge.pushToSaf(safRootUri, mirrorDir)
                        }
                        result
                    }
                } catch (e: Exception) {
                    GitSyncResult.Error("Force push failed: ${e.message}", e)
                }
            }

            return GitSyncResult.Error("Memo directory is not configured")
        }

        override suspend fun getMemoVersionHistory(
            dateKey: String,
            memoTimestamp: Long,
        ): List<MemoVersion> {
            val rootDir =
                resolveRootDir() ?: run {
                    val safRootUri = resolveSafRootUri() ?: return emptyList()
                    try {
                        runGitIo {
                            val mirrorDir = safGitMirrorBridge.mirrorDirectoryFor(safRootUri)
                            safGitMirrorBridge.pullFromSaf(safRootUri, mirrorDir)
                            mirrorDir
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to resolve SAF mirror for version history")
                        return emptyList()
                    }
                }
            val filename = "$dateKey.md"

            val history =
                runGitIo {
                    gitSyncEngine.getFileHistory(rootDir, filename)
                }
            if (history.isEmpty()) return emptyList()

            val versions = mutableListOf<MemoVersion>()
            for (pair in history) {
                val (commitHash, commitTime, commitMessage, fileContent) = pair

                val memos = markdownParser.parseContent(fileContent, dateKey)
                val matchingMemo = memos.firstOrNull { memo ->
                    abs(memo.timestamp - memoTimestamp) <= TIMESTAMP_TOLERANCE_MS
                } ?: continue

                versions.add(
                    MemoVersion(
                        commitHash = commitHash,
                        commitTime = commitTime,
                        commitMessage = commitMessage,
                        memoContent = matchingMemo.content,
                        isCurrent = false,
                    ),
                )
            }

            // Deduplicate unchanged versions while preserving commit order.
            val distinctVersions = versions.distinctBy { it.memoContent }
            if (distinctVersions.isEmpty()) return emptyList()

            val currentMemoContent = runCatching {
                val currentFile = File(rootDir, filename)
                if (!currentFile.exists()) return@runCatching null
                val currentMemos = markdownParser.parseFile(currentFile)
                currentMemos.firstOrNull { memo ->
                    abs(memo.timestamp - memoTimestamp) <= TIMESTAMP_TOLERANCE_MS
                }?.content
            }.getOrNull()

            return if (currentMemoContent.isNullOrBlank()) {
                distinctVersions.mapIndexed { index, version -> version.copy(isCurrent = index == 0) }
            } else {
                distinctVersions.map { version ->
                    version.copy(isCurrent = version.memoContent == currentMemoContent)
                }
            }
        }

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

        private suspend fun <T> runGitIo(block: suspend () -> T): T =
            withContext(Dispatchers.IO) {
                block()
            }
    }
