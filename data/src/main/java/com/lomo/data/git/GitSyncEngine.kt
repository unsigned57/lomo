package com.lomo.data.git

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.UnifiedSyncError
import com.lomo.domain.model.UnifiedSyncPhase
import com.lomo.domain.model.UnifiedSyncState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitSyncEngine
    @Inject
    constructor(
        dataStore: LomoDataStore,
        credentialStrategy: GitCredentialStrategy,
        primitives: GitRepositoryPrimitives,
    ) {
        private val workflow = GitSyncWorkflow(dataStore, credentialStrategy, primitives)

        private val initCoordinator = GitSyncInitCoordinator(workflow)
        private val commitSyncCoordinator = GitSyncCommitSyncCoordinator(workflow, dataStore)
        private val conflictRecoveryCoordinator =
            GitSyncConflictRecoveryCoordinator(workflow, dataStore, credentialStrategy, primitives)

        private val mutex = Mutex()
        private val _syncState = MutableStateFlow<UnifiedSyncState>(UnifiedSyncState.Idle)
        val syncState: StateFlow<UnifiedSyncState> = _syncState

        fun markNotConfigured() {
            _syncState.value = UnifiedSyncState.NotConfigured(SyncBackendType.GIT)
        }

        fun markError(message: String) {
            _syncState.value = errorState(message = message)
        }

        suspend fun initOrClone(
            rootDir: File,
            remoteUrl: String,
        ): GitSyncResult =
            mutex.withLock {
                _syncState.value = runningState(UnifiedSyncPhase.INITIALIZING)
                runNonFatalCatching {
                    initCoordinator.initOrClone(rootDir, remoteUrl).also(::publishResultState)
                }.getOrElse { error ->
                    Timber.e(error, "Git init/clone failed")
                    val message = error.message ?: UNKNOWN_ERROR_MESSAGE
                    _syncState.value = errorState(message = message, cause = error)
                    GitSyncResult.Error(message, error)
                }
            }

        suspend fun commitLocal(rootDir: File): GitSyncResult =
            mutex.withLock {
                runNonFatalCatching {
                    commitSyncCoordinator.commitLocal(rootDir)
                }.getOrElse { error ->
                    Timber.e(error, "Local commit failed")
                    GitSyncResult.Error("Local commit failed: ${error.message}", error)
                }
            }

        suspend fun sync(
            rootDir: File,
            remoteUrl: String,
        ): GitSyncResult =
            mutex.withLock {
                _syncState.value = runningState(UnifiedSyncPhase.COMMITTING)
                runNonFatalCatching {
                    val outcome =
                        commitSyncCoordinator.sync(rootDir, remoteUrl) { phase ->
                            _syncState.value = runningState(phase)
                        }
                    val result = outcome.result
                    when (result) {
                        is GitSyncResult.Success -> {
                            val syncedAt = outcome.syncedAtMs ?: System.currentTimeMillis()
                            _syncState.value = UnifiedSyncState.Success(SyncBackendType.GIT, syncedAt, result.message)
                        }

                        is GitSyncResult.Error -> {
                            _syncState.value = errorState(result)
                        }

                        is GitSyncResult.Conflict -> {
                            _syncState.value =
                                UnifiedSyncState.ConflictDetected(SyncBackendType.GIT, result.conflicts)
                        }

                        else -> {
                            _syncState.value = UnifiedSyncState.Idle
                        }
                    }
                    result
                }.getOrElse { error ->
                    Timber.e(error, "Git sync failed")
                    val message = error.message ?: UNKNOWN_ERROR_MESSAGE
                    _syncState.value = errorState(message = message, cause = error)
                    GitSyncResult.Error(message, error)
                }
            }

        suspend fun resetRepository(rootDir: File): GitSyncResult =
            mutex.withLock {
                val result = conflictRecoveryCoordinator.resetRepository(rootDir)
                if (result is GitSyncResult.Success) {
                    _syncState.value = UnifiedSyncState.Idle
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
                    _syncState.value = UnifiedSyncState.Idle
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
                        onPushingState = { _syncState.value = runningState(UnifiedSyncPhase.PUSHING) },
                    )
                if (outcome.result is GitSyncResult.Success) {
                    val syncedAt = outcome.syncedAtMs ?: System.currentTimeMillis()
                    _syncState.value =
                        UnifiedSyncState.Success(SyncBackendType.GIT, syncedAt, outcome.result.message)
                }
                outcome.result
            }

        suspend fun resolveConflicts(
            rootDir: File,
            remoteUrl: String,
            resolution: SyncConflictResolution,
            conflictSet: SyncConflictSet,
        ): GitSyncResult =
            mutex.withLock {
                _syncState.value = runningState(UnifiedSyncPhase.PUSHING)
                runNonFatalCatching {
                    val result =
                        conflictRecoveryCoordinator.applyConflictResolution(
                            rootDir = rootDir,
                            remoteUrl = remoteUrl,
                            resolution = resolution,
                            conflictSet = conflictSet,
                        )
                    publishResultState(result)
                    result
                }.getOrElse { error ->
                    Timber.e(error, "Git conflict resolution failed")
                    val message = error.message ?: UNKNOWN_ERROR_MESSAGE
                    _syncState.value = errorState(message = message, cause = error)
                    GitSyncResult.Error(message, error)
                }
            }

        private fun publishResultState(result: GitSyncResult) {
            when (result) {
                is GitSyncResult.Success -> {
                    _syncState.value =
                        UnifiedSyncState.Success(
                            provider = SyncBackendType.GIT,
                            timestamp = System.currentTimeMillis(),
                            summary = result.message,
                        )
                }

                is GitSyncResult.Error -> {
                    _syncState.value = errorState(result)
                }

                is GitSyncResult.Conflict -> {
                    _syncState.value =
                        UnifiedSyncState.ConflictDetected(SyncBackendType.GIT, result.conflicts)
                    }

                else -> {
                    _syncState.value = UnifiedSyncState.Idle
                }
            }
        }

        companion object {
            private const val UNKNOWN_ERROR_MESSAGE = "Unknown error"

            fun runningState(phase: UnifiedSyncPhase): UnifiedSyncState =
                UnifiedSyncState.Running(
                    provider = SyncBackendType.GIT,
                    phase = phase,
                )

            fun errorState(result: GitSyncResult.Error): UnifiedSyncState =
                errorState(
                    message = result.message,
                    providerCode = result.code.name,
                    cause = result.exception,
                )

            fun errorState(
                message: String,
                providerCode: String? = null,
                cause: Throwable? = null,
            ): UnifiedSyncState =
                UnifiedSyncState.Error(
                    error =
                        UnifiedSyncError(
                            provider = SyncBackendType.GIT,
                            message = message,
                            cause = cause,
                            providerCode = providerCode,
                        ),
                    timestamp = System.currentTimeMillis(),
                )
        }
    }
