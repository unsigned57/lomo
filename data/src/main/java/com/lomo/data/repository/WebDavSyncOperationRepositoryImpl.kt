package com.lomo.data.repository

import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.sync.SyncLayoutMigration
import com.lomo.data.util.runNonFatalCatching
import com.lomo.data.webdav.WebDavClient
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.WebDavSyncDirection
import com.lomo.domain.model.WebDavSyncReason
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.model.WebDavSyncState
import com.lomo.domain.model.WebDavSyncStatus
import com.lomo.domain.repository.WebDavSyncOperationRepository
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebDavSyncOperationRepositoryImpl
    @Inject
    constructor(
        private val syncExecutor: WebDavSyncExecutor,
        private val statusTester: WebDavSyncStatusTester,
    ) : WebDavSyncOperationRepository {
        private val syncGuard = AtomicBoolean(false)

        override suspend fun sync(): WebDavSyncResult =
            withSyncGuard(inProgressMessage = "WebDAV sync already in progress") {
                syncExecutor.performSync()
            }

        override suspend fun getStatus(): WebDavSyncStatus = statusTester.getStatus()

        override suspend fun testConnection(): WebDavSyncResult = statusTester.testConnection()

        private suspend fun withSyncGuard(
            inProgressMessage: String,
            block: suspend () -> WebDavSyncResult,
        ): WebDavSyncResult {
            if (!syncGuard.compareAndSet(false, true)) {
                return WebDavSyncResult.Success(inProgressMessage)
            }
            return try {
                block()
            } finally {
                syncGuard.set(false)
            }
        }
    }

@Singleton
class WebDavSyncStatusTester
    @Inject
    constructor(
        private val runtime: WebDavSyncRepositoryContext,
        private val support: WebDavSyncRepositorySupport,
        private val fileBridge: WebDavSyncFileBridge,
    ) {
        suspend fun getStatus(): WebDavSyncStatus {
            val config = support.resolveConfig() ?: return WebDavSyncStatus(0, 0, 0, null)
            val layout = SyncDirectoryLayout.resolve(runtime.dataStore)
            return support.runWebDavIo {
                runtime.stateHolder.state.value = WebDavSyncState.Listing
                val client = support.createClient(config)
                fileBridge.ensureRemoteDirectories(client, layout)
                val localFiles = fileBridge.localFiles(layout)
                val remoteFiles = fileBridge.remoteFiles(client, layout)
                val metadata = runtime.metadataDao.getAll().associateBy { it.relativePath }
                val plan = runtime.planner.plan(localFiles, remoteFiles, metadata)
                WebDavSyncStatus(
                    remoteFileCount = remoteFiles.size,
                    localFileCount = localFiles.size,
                    pendingChanges = plan.pendingChanges,
                    lastSyncTime = runtime.dataStore.webDavLastSyncTime.first().takeIf { it > 0L },
                )
            }
        }

        suspend fun testConnection(): WebDavSyncResult {
            val config = support.resolveConfig() ?: return support.notConfiguredResult()
            return runNonFatalCatching {
                support.runWebDavIo {
                    support.createClient(config).testConnection()
                }
                WebDavSyncResult.Success("WebDAV connection successful")
            }.getOrElse(support::mapConnectionTestError)
        }
    }

@Singleton
class WebDavSyncExecutor
    @Inject
    constructor(
        private val runtime: WebDavSyncRepositoryContext,
        private val support: WebDavSyncRepositorySupport,
        private val fileBridge: WebDavSyncFileBridge,
        private val actionApplier: WebDavSyncActionApplier,
    ) {
        suspend fun performSync(): WebDavSyncResult {
            val config = support.resolveConfig() ?: return support.notConfiguredResult()
            val layout = SyncDirectoryLayout.resolve(runtime.dataStore)
            return runNonFatalCatching {
                val result =
                    support.runWebDavIo {
                        val client = support.createClient(config)
                        val prepared = prepareSync(client, layout)
                        val execution = applyActions(prepared.normalActions, client, layout, prepared.localFiles)
                        fileBridge.persistMetadata(
                            client = client,
                            layout = layout,
                            localFiles = prepared.localFiles,
                            remoteFiles = prepared.remoteFiles,
                            actionOutcomes = execution.actionOutcomes,
                            localChanged = execution.localChanged,
                            remoteChanged = execution.remoteChanged,
                        )
                        buildSyncResult(prepared, execution)
                    }
                refreshAfterSync(result)
            }.getOrElse(support::mapError)
        }

        private suspend fun prepareSync(
            client: WebDavClient,
            layout: SyncDirectoryLayout,
        ): PreparedWebDavSync {
            runtime.stateHolder.state.value = WebDavSyncState.Initializing
            fileBridge.ensureRemoteDirectories(client, layout)
            SyncLayoutMigration.migrateWebDavRemote(client, layout)

            runtime.stateHolder.state.value = WebDavSyncState.Listing
            val localFiles = fileBridge.localFiles(layout)
            val remoteFiles =
                runNonFatalCatching {
                    fileBridge.remoteFiles(client, layout)
                }.getOrElse { error ->
                    throw IllegalStateException(
                        "Failed to list remote WebDAV files: ${error.message ?: WEBDAV_UNKNOWN_ERROR_MESSAGE}",
                        error,
                    )
                }
            val metadata = runtime.metadataDao.getAll().associateBy { it.relativePath }
            val plan = runtime.planner.plan(localFiles, remoteFiles, metadata)
            val conflictActions = plan.actions.filter { it.direction == WebDavSyncDirection.CONFLICT }
            val conflictSet = buildConflictSet(conflictActions, client, layout)
            if (conflictSet != null) {
                runtime.stateHolder.state.value = WebDavSyncState.ConflictDetected(conflictSet)
            }
            return PreparedWebDavSync(
                localFiles = localFiles,
                remoteFiles = remoteFiles,
                plan = plan,
                normalActions = plan.actions.filter { it.direction != WebDavSyncDirection.CONFLICT },
                conflictSet = conflictSet,
            )
        }

        private suspend fun buildConflictSet(
            actions: List<WebDavSyncAction>,
            client: WebDavClient,
            layout: SyncDirectoryLayout,
        ): SyncConflictSet? {
            val conflictFiles =
                actions.map { action ->
                    val localContent =
                        if (fileBridge.isMemoPath(action.path, layout)) {
                            runtime.markdownStorageDataSource.readFileIn(
                                MemoDirectoryType.MAIN,
                                fileBridge.extractMemoFilename(action.path, layout),
                            )
                        } else {
                            null
                        }
                    val remoteContent =
                        try {
                            String(client.get(action.path).bytes, StandardCharsets.UTF_8)
                        } catch (_: Exception) {
                            null
                        }
                    SyncConflictFile(
                        relativePath = action.path,
                        localContent = localContent,
                        remoteContent = remoteContent,
                        isBinary = !action.path.endsWith(WEBDAV_MEMO_SUFFIX),
                    )
                }
            return conflictFiles
                .takeIf(List<SyncConflictFile>::isNotEmpty)
                ?.let { files ->
                    SyncConflictSet(
                        source = SyncBackendType.WEBDAV,
                        files = files,
                        timestamp = System.currentTimeMillis(),
                    )
                }
        }

        private suspend fun applyActions(
            actions: List<WebDavSyncAction>,
            client: WebDavClient,
            layout: SyncDirectoryLayout,
            localFiles: Map<String, LocalWebDavFile>,
        ): ActionExecutionResult {
            val actionOutcomes = mutableMapOf<String, Pair<WebDavSyncDirection, WebDavSyncReason>>()
            val failedPaths = mutableListOf<String>()
            var localChanged = false
            var remoteChanged = false

            actions.forEach { action ->
                when (val result = actionApplier.applyAction(action, client, layout, localFiles)) {
                    ActionExecutionState.Skipped -> Unit
                    is ActionExecutionState.Applied -> {
                        localChanged = localChanged || result.localChanged
                        remoteChanged = remoteChanged || result.remoteChanged
                        actionOutcomes[action.path] = action.direction to action.reason
                    }

                    is ActionExecutionState.Failed -> {
                        failedPaths += result.path
                    }
                }
            }

            return ActionExecutionResult(
                actionOutcomes = actionOutcomes,
                failedPaths = failedPaths,
                localChanged = localChanged,
                remoteChanged = remoteChanged,
            )
        }

        private suspend fun refreshAfterSync(result: WebDavSyncResult): WebDavSyncResult {
            if (!result.shouldRefreshMemoCache()) {
                return result
            }

            return runNonFatalCatching {
                runtime.memoSynchronizer.refresh()
                val now = System.currentTimeMillis()
                runtime.dataStore.updateWebDavLastSyncTime(now)
                runtime.stateHolder.state.value = result.stateAfterRefresh(now)
                result
            }.getOrElse { error ->
                val message =
                    "WebDAV sync completed but memo refresh failed: " +
                        "${error.message ?: WEBDAV_UNKNOWN_ERROR_MESSAGE}"
                runtime.stateHolder.state.value = WebDavSyncState.Error(message, System.currentTimeMillis())
                WebDavSyncResult.Error(message, error, result.outcomesForRefreshFailure())
            }
        }

        private fun buildSyncResult(
            prepared: PreparedWebDavSync,
            execution: ActionExecutionResult,
        ): WebDavSyncResult =
            when {
                execution.failedPaths.isNotEmpty() -> {
                    val summary =
                        "WebDAV sync partially failed: ${execution.failedPaths.size} " +
                            "file(s) failed: ${execution.failedPaths.joinToString()}"
                    WebDavSyncResult.Error(
                        message = summary,
                        outcomes = prepared.plan.actions.map(WebDavSyncAction::toOutcome),
                    )
                }

                prepared.conflictSet != null ->
                    WebDavSyncResult.Conflict(
                        message = "${prepared.conflictSet.files.size} conflicting file(s) detected",
                        conflicts = prepared.conflictSet,
                    )

                prepared.plan.actions.isEmpty() ->
                    WebDavSyncResult.Success(
                        message = "WebDAV already up to date",
                        outcomes = prepared.plan.actions.map(WebDavSyncAction::toOutcome),
                    )

                else ->
                    WebDavSyncResult.Success(
                        message = "WebDAV sync completed",
                        outcomes = prepared.plan.actions.map(WebDavSyncAction::toOutcome),
                    )
            }
    }

@Singleton
class WebDavSyncActionApplier
    @Inject
    constructor(
        private val runtime: WebDavSyncRepositoryContext,
        private val fileBridge: WebDavSyncFileBridge,
    ) {
        internal suspend fun applyAction(
            action: WebDavSyncAction,
            client: WebDavClient,
            layout: SyncDirectoryLayout,
            localFiles: Map<String, LocalWebDavFile>,
        ): ActionExecutionState =
            runNonFatalCatching {
                when (action.direction) {
                    WebDavSyncDirection.UPLOAD -> uploadAction(action, client, layout, localFiles)
                    WebDavSyncDirection.DOWNLOAD -> downloadAction(action, client, layout)
                    WebDavSyncDirection.DELETE_LOCAL -> deleteLocalAction(action, layout)
                    WebDavSyncDirection.DELETE_REMOTE -> deleteRemoteAction(action, client)
                    WebDavSyncDirection.NONE,
                    WebDavSyncDirection.CONFLICT,
                    -> ActionExecutionState.Skipped
                }
            }.getOrElse { error ->
                Timber.e(error, "Failed to %s %s", action.operationName(), action.path)
                ActionExecutionState.Failed(action.path)
            }

        private suspend fun uploadAction(
            action: WebDavSyncAction,
            client: WebDavClient,
            layout: SyncDirectoryLayout,
            localFiles: Map<String, LocalWebDavFile>,
        ): ActionExecutionState {
            runtime.stateHolder.state.value = WebDavSyncState.Uploading
            val bytes = loadUploadBytes(action, layout) ?: return ActionExecutionState.Skipped
            client.put(
                path = action.path,
                bytes = bytes,
                contentType = fileBridge.contentTypeForPath(action.path, layout),
                lastModifiedHint = localFiles[action.path]?.lastModified,
            )
            return ActionExecutionState.Applied(localChanged = false, remoteChanged = true)
        }

        private suspend fun loadUploadBytes(
            action: WebDavSyncAction,
            layout: SyncDirectoryLayout,
        ): ByteArray? =
            if (fileBridge.isMemoPath(action.path, layout)) {
                val content =
                    runtime.markdownStorageDataSource.readFileIn(
                        MemoDirectoryType.MAIN,
                        fileBridge.extractMemoFilename(action.path, layout),
                    )
                if (content == null) {
                    Timber.w("Local memo missing during upload: %s, skipping", action.path)
                    null
                } else {
                    content.toByteArray(StandardCharsets.UTF_8)
                }
            } else {
                runtime.localMediaSyncStore.readBytes(action.path, layout)
            }

        private suspend fun downloadAction(
            action: WebDavSyncAction,
            client: WebDavClient,
            layout: SyncDirectoryLayout,
        ): ActionExecutionState {
            runtime.stateHolder.state.value = WebDavSyncState.Downloading
            val remoteFile = client.get(action.path)
            if (fileBridge.isMemoPath(action.path, layout)) {
                runtime.markdownStorageDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = fileBridge.extractMemoFilename(action.path, layout),
                    content = String(remoteFile.bytes, StandardCharsets.UTF_8),
                )
            } else {
                runtime.localMediaSyncStore.writeBytes(action.path, remoteFile.bytes, layout)
            }
            return ActionExecutionState.Applied(localChanged = true, remoteChanged = false)
        }

        private suspend fun deleteLocalAction(
            action: WebDavSyncAction,
            layout: SyncDirectoryLayout,
        ): ActionExecutionState {
            runtime.stateHolder.state.value = WebDavSyncState.Deleting
            if (fileBridge.isMemoPath(action.path, layout)) {
                runtime.markdownStorageDataSource.deleteFileIn(
                    MemoDirectoryType.MAIN,
                    fileBridge.extractMemoFilename(action.path, layout),
                )
            } else {
                runtime.localMediaSyncStore.delete(action.path, layout)
            }
            return ActionExecutionState.Applied(localChanged = true, remoteChanged = false)
        }

        private fun deleteRemoteAction(
            action: WebDavSyncAction,
            client: WebDavClient,
        ): ActionExecutionState {
            runtime.stateHolder.state.value = WebDavSyncState.Deleting
            client.delete(action.path)
            return ActionExecutionState.Applied(localChanged = false, remoteChanged = true)
        }
    }

private data class PreparedWebDavSync(
    val localFiles: Map<String, LocalWebDavFile>,
    val remoteFiles: Map<String, RemoteWebDavFile>,
    val plan: WebDavSyncPlan,
    val normalActions: List<WebDavSyncAction>,
    val conflictSet: SyncConflictSet?,
)

private data class ActionExecutionResult(
    val actionOutcomes: Map<String, Pair<WebDavSyncDirection, WebDavSyncReason>>,
    val failedPaths: List<String>,
    val localChanged: Boolean,
    val remoteChanged: Boolean,
)

internal sealed interface ActionExecutionState {
    data object Skipped : ActionExecutionState

    data class Applied(
        val localChanged: Boolean,
        val remoteChanged: Boolean,
    ) : ActionExecutionState

    data class Failed(
        val path: String,
    ) : ActionExecutionState
}

private fun WebDavSyncAction.operationName(): String =
    when (direction) {
        WebDavSyncDirection.UPLOAD -> "upload"
        WebDavSyncDirection.DOWNLOAD -> "download"
        WebDavSyncDirection.DELETE_LOCAL -> "delete local"
        WebDavSyncDirection.DELETE_REMOTE -> "delete remote"
        WebDavSyncDirection.NONE -> "sync"
        WebDavSyncDirection.CONFLICT -> "conflict"
    }

private fun WebDavSyncResult.shouldRefreshMemoCache(): Boolean =
    this is WebDavSyncResult.Success ||
        (this is WebDavSyncResult.Error && outcomes.isNotEmpty())

private fun WebDavSyncResult.stateAfterRefresh(timestamp: Long): WebDavSyncState =
    when (this) {
        is WebDavSyncResult.Success -> WebDavSyncState.Success(timestamp, message)
        is WebDavSyncResult.Error -> WebDavSyncState.Error(message, timestamp)
        is WebDavSyncResult.Conflict -> WebDavSyncState.ConflictDetected(conflicts)
        WebDavSyncResult.NotConfigured -> WebDavSyncState.NotConfigured
    }

private fun WebDavSyncResult.outcomesForRefreshFailure() =
    when (this) {
        is WebDavSyncResult.Success -> outcomes
        is WebDavSyncResult.Error -> outcomes
        is WebDavSyncResult.Conflict -> emptyList()
        WebDavSyncResult.NotConfigured -> emptyList()
    }
