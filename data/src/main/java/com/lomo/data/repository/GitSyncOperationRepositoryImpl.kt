package com.lomo.data.repository

import com.lomo.data.git.GitSyncErrorMessages
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.sync.SyncLayoutMigration
import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.GitSyncStatus
import com.lomo.domain.repository.GitSyncOperationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitSyncOperationRepositoryImpl
    @Inject
    constructor(
        private val runtime: GitSyncRepositoryContext,
        private val initAndSyncExecutor: GitSyncInitAndSyncExecutor,
        private val statusExecutor: GitSyncStatusExecutor,
        private val maintenanceExecutor: GitSyncMaintenanceExecutor,
    ) : GitSyncOperationRepository {
        private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val isSyncInProgress = AtomicBoolean(false)

        init {
            @OptIn(FlowPreview::class)
            syncScope.launch {
                runtime.memoSynchronizer.outboxDrainCompleted
                    .debounce(MEMO_CHANGE_DEBOUNCE_MS)
                    .collect {
                        runNonFatalCatching {
                            if (runtime.dataStore.gitSyncEnabled.first()) {
                                commitLocal()
                            }
                        }.onFailure { error ->
                            Timber.w(error, "Event-driven git commit failed")
                        }
                    }
            }
        }

        override suspend fun initOrClone(): GitSyncResult = initAndSyncExecutor.initOrClone()

        override suspend fun sync(): GitSyncResult =
            withSyncGuard(inProgressMessage = "Sync already in progress") {
                initAndSyncExecutor.sync()
            }

        override suspend fun getStatus(): GitSyncStatus = statusExecutor.getStatus()

        override suspend fun testConnection(): GitSyncResult = statusExecutor.testConnection()

        override suspend fun resetRepository(): GitSyncResult = maintenanceExecutor.resetRepository()

        override suspend fun resetLocalBranchToRemote(): GitSyncResult = maintenanceExecutor.resetLocalBranchToRemote()

        override suspend fun forcePushLocalToRemote(): GitSyncResult = maintenanceExecutor.forcePushLocalToRemote()

        private suspend fun commitLocal(): GitSyncResult =
            withSyncGuard(inProgressMessage = "Sync in progress") {
                initAndSyncExecutor.commitLocal()
            }

        private suspend fun withSyncGuard(
            inProgressMessage: String,
            block: suspend () -> GitSyncResult,
        ): GitSyncResult {
            if (!isSyncInProgress.compareAndSet(false, true)) {
                return GitSyncResult.Success(inProgressMessage)
            }
            return try {
                block()
            } finally {
                isSyncInProgress.set(false)
            }
        }
    }

@Singleton
class GitSyncInitAndSyncExecutor
    @Inject
    constructor(
        private val runtime: GitSyncRepositoryContext,
        private val support: GitSyncRepositorySupport,
        private val memoMirror: GitSyncMemoMirror,
    ) {
        suspend fun initOrClone(): GitSyncResult =
            when (val readyState = loadReadyContext(requireEnabled = false)) {
                is GitSyncReadyState.Failure -> readyState.result
                is GitSyncReadyState.Ready -> {
                    when (val targetState = resolveRepoTarget(readyState.context, INIT_SAF_MIRROR_FAILURE_MESSAGE)) {
                        is GitSyncTargetState.Failure -> targetState.result
                        is GitSyncTargetState.Ready -> {
                            val target = targetState.target
                            mirrorRepoTargetBeforeGit(target, readyState.context.layout)
                            val result =
                                support.runGitIo {
                                    runtime.gitSyncEngine.initOrClone(target.repoDir, readyState.context.remoteUrl)
                                }
                            pushSafMirrorOrError(
                                runtime = runtime,
                                support = support,
                                target = target,
                                successResult = result,
                                failureMessage = INIT_SAF_MIRROR_FAILURE_MESSAGE,
                            ) ?: result
                        }
                    }
                }
            }

        suspend fun commitLocal(): GitSyncResult {
            val enabled = runtime.dataStore.gitSyncEnabled.first()
            val layout = SyncDirectoryLayout.resolve(runtime.dataStore)
            val directRootDir = support.resolveRootDir()
            val safRootUri =
                if (directRootDir == null && !layout.allSameDirectory) {
                    support.resolveSafRootUri()
                } else {
                    null
                }
            val repoDir =
                when {
                    directRootDir != null -> support.resolveGitRepoDir(directRootDir, layout)
                    !safRootUri.isNullOrBlank() -> support.resolveGitRepoDirForUri(safRootUri)
                    else -> null
                }
            return when {
                !enabled -> GitSyncResult.NotConfigured
                directRootDir == null && layout.allSameDirectory ->
                    GitSyncResult.Success("SAF mode, skipping local commit")
                repoDir == null -> GitSyncResult.Success("Not configured")
                !File(repoDir, GIT_DIR_NAME).exists() -> GitSyncResult.Success("Not initialized yet")
                else -> {
                    memoMirror.mirrorMemoToRepo(repoDir, layout)
                    support.runGitIo {
                        runtime.gitSyncEngine.commitLocal(repoDir)
                    }
                }
            }
        }

        suspend fun sync(): GitSyncResult =
            when (val readyState = loadReadyContext(requireEnabled = true)) {
                is GitSyncReadyState.Failure -> readyState.result
                is GitSyncReadyState.Ready -> {
                    when (val targetState = resolveRepoTarget(readyState.context, PREPARE_SAF_MIRROR_FAILURE_MESSAGE)) {
                        is GitSyncTargetState.Failure -> targetState.result
                        is GitSyncTargetState.Ready -> {
                            val target = targetState.target
                            val initResult = ensureInitializedRepo(target, readyState.context.remoteUrl)
                            initResult ?: finalizeSyncResult(runPrimarySync(target, readyState.context), target)
                        }
                    }
                }
            }

        private suspend fun loadReadyContext(requireEnabled: Boolean): GitSyncReadyState {
            val enabled = runtime.dataStore.gitSyncEnabled.first()
            val remoteUrl = runtime.dataStore.gitRemoteUrl.first()
            val tokenMissing = runtime.credentialStore.getToken().isNullOrBlank()
            val layout = SyncDirectoryLayout.resolve(runtime.dataStore)
            val directRootDir = support.resolveRootDir()
            val safRootUri = support.resolveSafRootUri()
            val failureResult =
                when {
                    requireEnabled && !enabled -> {
                        runtime.gitSyncEngine.markNotConfigured()
                        GitSyncResult.NotConfigured
                    }

                    remoteUrl.isNullOrBlank() -> {
                        runtime.gitSyncEngine.markNotConfigured()
                        GitSyncResult.NotConfigured
                    }

                    tokenMissing -> {
                        runtime.gitSyncEngine.markError(GitSyncErrorMessages.PAT_REQUIRED)
                        GitSyncResult.Error(GitSyncErrorMessages.PAT_REQUIRED)
                    }

                    directRootDir == null && safRootUri.isNullOrBlank() -> {
                        runtime.gitSyncEngine.markError(MEMO_DIRECTORY_NOT_CONFIGURED_MESSAGE)
                        GitSyncResult.DirectPathRequired
                    }

                    else -> null
                }
            return failureResult?.let(GitSyncReadyState::Failure)
                ?: GitSyncReadyState.Ready(
                    GitSyncReadyContext(
                        remoteUrl = checkNotNull(remoteUrl),
                        layout = layout,
                        directRootDir = directRootDir,
                        safRootUri = safRootUri,
                    ),
                )
        }

        private suspend fun resolveRepoTarget(
            context: GitSyncReadyContext,
            safMirrorFailureMessage: String,
        ): GitSyncTargetState {
            val resolvedTarget =
                when {
                    context.directRootDir != null -> {
                        GitSyncTarget(
                            repoDir = support.resolveGitRepoDir(context.directRootDir, context.layout),
                            safRootUri = null,
                            usesSafMirror = false,
                            layout = context.layout,
                        )
                    }

                    !context.safRootUri.isNullOrBlank() && !context.layout.allSameDirectory -> {
                        GitSyncTarget(
                            repoDir = support.resolveGitRepoDirForUri(context.safRootUri),
                            safRootUri = context.safRootUri,
                            usesSafMirror = false,
                            layout = context.layout,
                        )
                    }

                    !context.safRootUri.isNullOrBlank() -> {
                        val mirrorTarget =
                            runNonFatalCatching {
                            GitSyncTarget(
                                repoDir = support.prepareSafMirror(context.safRootUri),
                                safRootUri = context.safRootUri,
                                usesSafMirror = true,
                                layout = context.layout,
                            )
                            }
                        val error = mirrorTarget.exceptionOrNull()
                        if (error != null) {
                            val message = error.message ?: safMirrorFailureMessage
                            runtime.gitSyncEngine.markError(message)
                            return GitSyncTargetState.Failure(GitSyncResult.Error(message, error))
                        }
                        mirrorTarget.getOrThrow()
                    }

                    else -> null
                }
            return resolvedTarget?.let(GitSyncTargetState::Ready)
                ?: GitSyncTargetState.Failure(GitSyncResult.DirectPathRequired)
        }

        private suspend fun ensureInitializedRepo(
            target: GitSyncTarget,
            remoteUrl: String,
        ): GitSyncResult? {
            val gitDir = File(target.repoDir, GIT_DIR_NAME)
            return if (gitDir.exists()) {
                null
            } else {
                support.runGitIo {
                    runtime.gitSyncEngine.initOrClone(target.repoDir, remoteUrl)
                }.takeUnless { it is GitSyncResult.Success }
            }
        }

        private suspend fun runPrimarySync(
            target: GitSyncTarget,
            context: GitSyncReadyContext,
        ): GitSyncResult {
            if (SyncLayoutMigration.migrateGitRepo(target.repoDir, context.layout)) {
                support.runGitIo { runtime.gitSyncEngine.commitLocal(target.repoDir) }
            }

            var result =
                support.runGitIo {
                    runtime.gitSyncEngine.sync(target.repoDir, context.remoteUrl)
                }
            if (result is GitSyncResult.Success) {
                val mediaSummary = runtime.gitMediaSyncBridge.reconcile(target.repoDir, context.layout)
                if (mediaSummary.repoChanged) {
                    result =
                        support.runGitIo {
                            runtime.gitSyncEngine.sync(target.repoDir, context.remoteUrl)
                        }
                }
                if (result is GitSyncResult.Success) {
                    runtime.gitMediaSyncBridge.reconcile(target.repoDir, context.layout)
                }
            }
            return result
        }

        private suspend fun finalizeSyncResult(
            result: GitSyncResult,
            target: GitSyncTarget,
        ): GitSyncResult {
            if (result !is GitSyncResult.Success) {
                return result
            }

            memoMirror.mirrorMemoFromRepo(target.repoDir, target.layout)
            val safMirrorError =
                pushSafMirrorOrError(
                    runtime = runtime,
                    support = support,
                    target = target,
                    successResult = result,
                    failureMessage = PUSH_SAF_MIRROR_FAILURE_MESSAGE,
                )
            return safMirrorError ?: refreshAfterSyncOrError(runtime) ?: result
        }

        private suspend fun mirrorRepoTargetBeforeGit(
            target: GitSyncTarget,
            layout: SyncDirectoryLayout,
        ) {
            if (!target.usesSafMirror) {
                memoMirror.mirrorMemoToRepo(target.repoDir, layout)
            }
        }

        private companion object {
            private const val GIT_DIR_NAME = ".git"
            private const val INIT_SAF_MIRROR_FAILURE_MESSAGE = "Failed to initialize SAF mirror for git sync"
            private const val PREPARE_SAF_MIRROR_FAILURE_MESSAGE = "Failed to prepare SAF mirror for git sync"
            private const val PUSH_SAF_MIRROR_FAILURE_MESSAGE = "Failed to write synced files back to SAF storage"
        }
    }

@Singleton
class GitSyncStatusExecutor
    @Inject
    constructor(
        private val runtime: GitSyncRepositoryContext,
        private val support: GitSyncRepositorySupport,
    ) {
        suspend fun getStatus(): GitSyncStatus {
            val layout = SyncDirectoryLayout.resolve(runtime.dataStore)
            val repoDir = resolveStatusRepoDir(layout)
            val status =
                repoDir?.let { resolvedRepoDir ->
                    support.runGitIo {
                        runtime.gitSyncQueryCoordinator.getStatus(resolvedRepoDir)
                    }
                } ?: EMPTY_GIT_STATUS
            val lastSync = runtime.dataStore.gitLastSyncTime.first()
            return status.copy(lastSyncTime = lastSync.takeIf { it > 0L })
        }

        suspend fun testConnection(): GitSyncResult {
            val remoteUrl = runtime.dataStore.gitRemoteUrl.first()
            val token = runtime.credentialStore.getToken()
            return when {
                remoteUrl.isNullOrBlank() -> GitSyncResult.Error(REPOSITORY_URL_NOT_CONFIGURED_MESSAGE)
                token.isNullOrBlank() -> GitSyncResult.Error(GitSyncErrorMessages.PAT_REQUIRED)
                else ->
                    support.runGitIo {
                        runtime.gitSyncQueryCoordinator.testConnection(remoteUrl)
                    }
            }
        }

        private suspend fun resolveStatusRepoDir(layout: SyncDirectoryLayout): File? {
            val directRootDir = support.resolveRootDir()
            val safRootUri = support.resolveSafRootUri()
            return when {
                directRootDir != null -> support.resolveGitRepoDir(directRootDir, layout)
                safRootUri.isNullOrBlank() -> null
                !layout.allSameDirectory -> support.resolveGitRepoDirForUri(safRootUri)
                else ->
                    try {
                        support.prepareSafMirror(safRootUri)
                    } catch (_: Exception) {
                        null
                    }
            }
        }

        private companion object {
            private val EMPTY_GIT_STATUS =
                GitSyncStatus(
                    hasLocalChanges = false,
                    aheadCount = 0,
                    behindCount = 0,
                    lastSyncTime = null,
                )
        }
    }

@Singleton
class GitSyncMaintenanceExecutor
    @Inject
    constructor(
        private val runtime: GitSyncRepositoryContext,
        private val support: GitSyncRepositorySupport,
        private val memoMirror: GitSyncMemoMirror,
    ) {
        suspend fun resetRepository(): GitSyncResult {
            val layout = SyncDirectoryLayout.resolve(runtime.dataStore)
            val directRootDir = support.resolveRootDir()
            val safRootUri = support.resolveSafRootUri()
            return when {
                directRootDir != null -> {
                    val repoDir = support.resolveGitRepoDir(directRootDir, layout)
                    support.runGitIo {
                        runtime.gitSyncEngine.resetRepository(repoDir)
                    }
                }

                !safRootUri.isNullOrBlank() -> {
                    val repoDir =
                        if (layout.allSameDirectory) {
                            support.runGitIo { runtime.safGitMirrorBridge.mirrorDirectoryFor(safRootUri) }
                        } else {
                            support.resolveGitRepoDirForUri(safRootUri)
                        }
                    runCatching {
                        support.runGitIo {
                            runtime.gitSyncEngine.resetRepository(repoDir)
                        }
                    }.getOrElse { error ->
                        GitSyncResult.Error("Reset failed: ${error.message}", error)
                    }
                }

                else -> GitSyncResult.Error(MEMO_DIRECTORY_NOT_CONFIGURED_MESSAGE)
            }
        }

        suspend fun resetLocalBranchToRemote(): GitSyncResult {
            val remoteUrl = runtime.dataStore.gitRemoteUrl.first()
            val token = runtime.credentialStore.getToken()
            val preconditionError =
                when {
                    remoteUrl.isNullOrBlank() -> GitSyncResult.Error(REPOSITORY_URL_NOT_CONFIGURED_MESSAGE)
                    token.isNullOrBlank() -> GitSyncResult.Error(GitSyncErrorMessages.PAT_REQUIRED)
                    else -> null
                }
            if (preconditionError != null) {
                return preconditionError
            }
            val resolvedRemoteUrl = checkNotNull(remoteUrl)

            val layout = SyncDirectoryLayout.resolve(runtime.dataStore)
            val directRootDir = support.resolveRootDir()
            val safRootUri = support.resolveSafRootUri()
            return when {
                directRootDir != null -> {
                    val repoDir = support.resolveGitRepoDir(directRootDir, layout)
                    support.runGitIo {
                        runtime.gitSyncEngine.resetLocalBranchToRemote(repoDir, resolvedRemoteUrl)
                    }
                }

                !safRootUri.isNullOrBlank() -> {
                    runCatching {
                        if (layout.allSameDirectory) {
                            val repoDir = support.prepareSafMirror(safRootUri)
                            val result =
                                support.runGitIo {
                                    runtime.gitSyncEngine.resetLocalBranchToRemote(repoDir, resolvedRemoteUrl)
                                }
                            pushSafMirrorOrError(
                                runtime = runtime,
                                support = support,
                                target = GitSyncTarget(repoDir, safRootUri, true, layout),
                                successResult = result,
                                failureMessage = RESET_TO_REMOTE_FAILURE_MESSAGE,
                            ) ?: result
                        } else {
                            val repoDir = support.resolveGitRepoDirForUri(safRootUri)
                            val result =
                                support.runGitIo {
                                    runtime.gitSyncEngine.resetLocalBranchToRemote(repoDir, resolvedRemoteUrl)
                                }
                            if (result is GitSyncResult.Success) {
                                memoMirror.mirrorMemoFromRepo(repoDir, layout)
                            }
                            result
                        }
                    }.getOrElse { error ->
                        GitSyncResult.Error("Reset to remote failed: ${error.message}", error)
                    }
                }

                else -> GitSyncResult.Error(MEMO_DIRECTORY_NOT_CONFIGURED_MESSAGE)
            }
        }

        suspend fun forcePushLocalToRemote(): GitSyncResult {
            val remoteUrl = runtime.dataStore.gitRemoteUrl.first()
            val token = runtime.credentialStore.getToken()
            val preconditionError =
                when {
                    remoteUrl.isNullOrBlank() -> GitSyncResult.Error(REPOSITORY_URL_NOT_CONFIGURED_MESSAGE)
                    token.isNullOrBlank() -> GitSyncResult.Error(GitSyncErrorMessages.PAT_REQUIRED)
                    else -> null
                }
            if (preconditionError != null) {
                return preconditionError
            }
            val resolvedRemoteUrl = checkNotNull(remoteUrl)

            val layout = SyncDirectoryLayout.resolve(runtime.dataStore)
            val directRootDir = support.resolveRootDir()
            val safRootUri = support.resolveSafRootUri()
            return when {
                directRootDir != null -> {
                    val repoDir = support.resolveGitRepoDir(directRootDir, layout)
                    memoMirror.mirrorMemoToRepo(repoDir, layout)
                    support.runGitIo {
                        runtime.gitSyncEngine.forcePushLocalToRemote(repoDir, resolvedRemoteUrl)
                    }
                }

                !safRootUri.isNullOrBlank() -> {
                    runCatching {
                        if (layout.allSameDirectory) {
                            val repoDir = support.prepareSafMirror(safRootUri)
                            val result =
                                support.runGitIo {
                                    runtime.gitSyncEngine.forcePushLocalToRemote(repoDir, resolvedRemoteUrl)
                                }
                            pushSafMirrorOrError(
                                runtime = runtime,
                                support = support,
                                target = GitSyncTarget(repoDir, safRootUri, true, layout),
                                successResult = result,
                                failureMessage = FORCE_PUSH_FAILURE_MESSAGE,
                            ) ?: result
                        } else {
                            val repoDir = support.resolveGitRepoDirForUri(safRootUri)
                            memoMirror.mirrorMemoToRepo(repoDir, layout)
                            support.runGitIo {
                                runtime.gitSyncEngine.forcePushLocalToRemote(repoDir, resolvedRemoteUrl)
                            }
                        }
                    }.getOrElse { error ->
                        GitSyncResult.Error("Force push failed: ${error.message}", error)
                    }
                }

                else -> GitSyncResult.Error(MEMO_DIRECTORY_NOT_CONFIGURED_MESSAGE)
            }
        }

        private companion object {
            private const val RESET_TO_REMOTE_FAILURE_MESSAGE = "Reset to remote failed"
            private const val FORCE_PUSH_FAILURE_MESSAGE = "Force push failed"
        }
    }

private data class GitSyncReadyContext(
    val remoteUrl: String,
    val layout: SyncDirectoryLayout,
    val directRootDir: File?,
    val safRootUri: String?,
)

private data class GitSyncTarget(
    val repoDir: File,
    val safRootUri: String?,
    val usesSafMirror: Boolean,
    val layout: SyncDirectoryLayout,
)

private sealed interface GitSyncReadyState {
    data class Ready(
        val context: GitSyncReadyContext,
    ) : GitSyncReadyState

    data class Failure(
        val result: GitSyncResult,
    ) : GitSyncReadyState
}

private sealed interface GitSyncTargetState {
    data class Ready(
        val target: GitSyncTarget,
    ) : GitSyncTargetState

    data class Failure(
        val result: GitSyncResult,
    ) : GitSyncTargetState
}

private suspend fun pushSafMirrorOrError(
    runtime: GitSyncRepositoryContext,
    support: GitSyncRepositorySupport,
    target: GitSyncTarget,
    successResult: GitSyncResult,
    failureMessage: String,
): GitSyncResult.Error? {
    val shouldPush =
        successResult is GitSyncResult.Success &&
            target.usesSafMirror &&
            !target.safRootUri.isNullOrBlank()
    return if (shouldPush) {
        runNonFatalCatching {
            support.pushSafMirror(target.safRootUri, target.repoDir)
            null
        }.getOrElse { error ->
            val message = error.message ?: failureMessage
            runtime.gitSyncEngine.markError(message)
            GitSyncResult.Error(message, error)
        }
    } else {
        null
    }
}

private suspend fun refreshAfterSyncOrError(
    runtime: GitSyncRepositoryContext,
): GitSyncResult.Error? =
    runNonFatalCatching {
        runtime.memoSynchronizer.refresh()
        null
    }.getOrElse { error ->
        val causeMessage = error.message?.takeIf(String::isNotBlank) ?: "unknown memo refresh error"
        val message = "Git sync completed but memo refresh failed: $causeMessage"
        runtime.gitSyncEngine.markError(message)
        Timber.w(error, message)
        GitSyncResult.Error(message, error)
    }
