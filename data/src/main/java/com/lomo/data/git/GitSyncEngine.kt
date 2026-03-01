package com.lomo.data.git

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.GitSyncStatus
import com.lomo.domain.model.SyncEngineState
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

@Singleton
class GitSyncEngine
    @Inject
    constructor(
        dataStore: LomoDataStore,
        credentialStrategy: GitCredentialStrategy,
        primitives: GitRepositoryPrimitives,
    ) {
        private val workflow = GitSyncWorkflow(dataStore, credentialStrategy, primitives)
        private val fileHistoryReader = GitFileHistoryReader(primitives)

        private val initCoordinator = GitSyncInitCoordinator(workflow)
        private val commitSyncCoordinator = GitSyncCommitSyncCoordinator(workflow, dataStore)
        private val queryTestCoordinator = GitSyncQueryTestCoordinator(credentialStrategy, fileHistoryReader)
        private val conflictRecoveryCoordinator =
            GitSyncConflictRecoveryCoordinator(workflow, dataStore, credentialStrategy, primitives)

        private val mutex = Mutex()
        private val _syncState = MutableStateFlow<SyncEngineState>(SyncEngineState.Idle)
        val syncState: StateFlow<SyncEngineState> = _syncState

        fun markNotConfigured() {
            _syncState.value = SyncEngineState.NotConfigured
        }

        fun markError(message: String) {
            _syncState.value = SyncEngineState.Error(message, System.currentTimeMillis())
        }

        suspend fun initOrClone(
            rootDir: File,
            remoteUrl: String,
        ): GitSyncResult =
            mutex.withLock {
                _syncState.value = SyncEngineState.Initializing
                try {
                    initCoordinator.initOrClone(rootDir, remoteUrl).also(::publishResultState)
                } catch (e: Exception) {
                    Timber.e(e, "Git init/clone failed")
                    val message = e.message ?: "Unknown error"
                    _syncState.value = SyncEngineState.Error(message, System.currentTimeMillis())
                    GitSyncResult.Error(message, e)
                }
            }

        suspend fun commitLocal(rootDir: File): GitSyncResult =
            mutex.withLock {
                try {
                    commitSyncCoordinator.commitLocal(rootDir)
                } catch (e: Exception) {
                    Timber.e(e, "Local commit failed")
                    GitSyncResult.Error("Local commit failed: ${e.message}", e)
                }
            }

        suspend fun sync(
            rootDir: File,
            remoteUrl: String,
        ): GitSyncResult =
            mutex.withLock {
                _syncState.value = SyncEngineState.Syncing.Committing
                try {
                    val outcome =
                        commitSyncCoordinator.sync(rootDir, remoteUrl) { phase ->
                            _syncState.value = phase
                        }
                    val result = outcome.result
                    when (result) {
                        is GitSyncResult.Success -> {
                            val syncedAt = outcome.syncedAtMs ?: System.currentTimeMillis()
                            _syncState.value = SyncEngineState.Success(syncedAt, result.message)
                        }
                        is GitSyncResult.Error ->
                            _syncState.value = SyncEngineState.Error(
                                result.message,
                                System.currentTimeMillis(),
                            )
                        else -> _syncState.value = SyncEngineState.Idle
                    }
                    result
                } catch (e: Exception) {
                    Timber.e(e, "Git sync failed")
                    val message = e.message ?: "Unknown error"
                    _syncState.value = SyncEngineState.Error(message, System.currentTimeMillis())
                    GitSyncResult.Error(message, e)
                }
            }

        data class FileHistoryEntry(
            val commitHash: String,
            val commitTime: Long,
            val commitMessage: String,
            val fileContent: String,
        )

        fun getFileHistory(
            rootDir: File,
            filename: String,
            maxCount: Int = 50,
        ): List<FileHistoryEntry> =
            queryTestCoordinator
                .getFileHistory(rootDir = rootDir, filename = filename, maxCount = maxCount)
                .map { entry ->
                    FileHistoryEntry(
                        commitHash = entry.commitHash,
                        commitTime = entry.commitTime,
                        commitMessage = entry.commitMessage,
                        fileContent = entry.fileContent,
                    )
                }

        fun getStatus(rootDir: File): GitSyncStatus = queryTestCoordinator.getStatus(rootDir)

        fun testConnection(remoteUrl: String): GitSyncResult = queryTestCoordinator.testConnection(remoteUrl)

        suspend fun resetRepository(rootDir: File): GitSyncResult =
            mutex.withLock {
                val result = conflictRecoveryCoordinator.resetRepository(rootDir)
                if (result is GitSyncResult.Success) {
                    _syncState.value = SyncEngineState.Idle
                }
                result
            }

        suspend fun resetLocalBranchToRemote(
            rootDir: File,
            remoteUrl: String,
        ): GitSyncResult =
            mutex.withLock {
                val result = conflictRecoveryCoordinator.resetLocalBranchToRemote(rootDir, remoteUrl)
                if (result is GitSyncResult.Success) {
                    _syncState.value = SyncEngineState.Idle
                }
                result
            }

        suspend fun forcePushLocalToRemote(
            rootDir: File,
            remoteUrl: String,
        ): GitSyncResult =
            mutex.withLock {
                val outcome =
                    conflictRecoveryCoordinator.forcePushLocalToRemote(
                        rootDir = rootDir,
                        remoteUrl = remoteUrl,
                        onPushingState = { _syncState.value = SyncEngineState.Syncing.Pushing },
                    )
                if (outcome.result is GitSyncResult.Success) {
                    val syncedAt = outcome.syncedAtMs ?: System.currentTimeMillis()
                    _syncState.value = SyncEngineState.Success(syncedAt, outcome.result.message)
                }
                outcome.result
            }

        private fun publishResultState(result: GitSyncResult) {
            when (result) {
                is GitSyncResult.Success ->
                    _syncState.value = SyncEngineState.Success(System.currentTimeMillis(), result.message)
                is GitSyncResult.Error ->
                    _syncState.value = SyncEngineState.Error(result.message, System.currentTimeMillis())
                else -> _syncState.value = SyncEngineState.Idle
            }
        }
    }
