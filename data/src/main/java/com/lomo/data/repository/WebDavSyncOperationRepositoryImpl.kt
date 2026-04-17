package com.lomo.data.repository

import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.sync.SyncLayoutMigration
import com.lomo.data.util.runNonFatalCatching
import com.lomo.data.webdav.WebDavClient
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictAutoResolutionAdvisor
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSessionKind
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.WebDavSyncDirection
import com.lomo.domain.model.WebDavSyncReason
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.model.WebDavSyncState
import com.lomo.domain.model.WebDavSyncStatus
import com.lomo.domain.repository.WebDavSyncOperationRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
        private val stateHolder: WebDavSyncStateHolder,
        private val pendingConflictStore: PendingSyncConflictStore = DisabledPendingSyncConflictStore,
    ) : WebDavSyncOperationRepository {
        private val syncGuard = AtomicBoolean(false)

        override suspend fun sync(): WebDavSyncResult =
            withSyncGuard(inProgressMessage = "WebDAV sync already in progress") {
                restorePendingConflictIfPresent()?.let { pending ->
                    return@withSyncGuard pending
                }
                val result = syncExecutor.performSync()
                clearPendingConflictsOnSuccess(result)
                result
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

        private suspend fun restorePendingConflictIfPresent(): WebDavSyncResult? {
            val pending = pendingConflictStore.read(SyncBackendType.WEBDAV) ?: return null
            stateHolder.state.value = WebDavSyncState.ConflictDetected(pending)
            return WebDavSyncResult.Conflict("Pending conflicts remain", pending)
        }

        private suspend fun clearPendingConflictsOnSuccess(result: WebDavSyncResult) {
            if (result is WebDavSyncResult.Success) {
                pendingConflictStore.clear(SyncBackendType.WEBDAV)
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
        private val pendingConflictStore: PendingSyncConflictStore = DisabledPendingSyncConflictStore,
    ) {
        private val verificationGate = WebDavPreparedActionVerificationGate()

        suspend fun performSync(): WebDavSyncResult {
            val config = support.resolveConfig() ?: return support.notConfiguredResult()
            val layout = SyncDirectoryLayout.resolve(runtime.dataStore)
            return runNonFatalCatching {
                val executionResult =
                    support.runWebDavIo {
                        val client = support.createClient(config)
                        val prepared = prepareSync(client, layout)
                        val verified = verificationGate.verify(prepared, client, fileBridge)

                        val conflictActions =
                            verified.plan.actions.filter { it.direction == WebDavSyncDirection.CONFLICT }
                        val normalActions =
                            verified.plan.actions.filter { it.direction != WebDavSyncDirection.CONFLICT }

                        val autoResolution =
                            autoResolveConflicts(
                                conflictActions = conflictActions,
                                client = client,
                                layout = layout,
                                localFiles = verified.localFiles,
                                remoteFiles = verified.remoteFiles,
                            )
                        val allNormalActions = normalActions + autoResolution.resolvedActions

                        val conflictSet =
                            autoResolution.unresolvedFiles
                                .takeIf(List<SyncConflictFile>::isNotEmpty)
                                ?.let { files ->
                                    SyncConflictSet(
                                        source = SyncBackendType.WEBDAV,
                                        files = files,
                                        timestamp = System.currentTimeMillis(),
                                        sessionKind =
                                            if (prepared.isInitialSync) {
                                                SyncConflictSessionKind.INITIAL_SYNC_PREVIEW
                                            } else {
                                                SyncConflictSessionKind.STANDARD_CONFLICT
                                            },
                                    )
                                }
                        if (conflictSet != null) {
                            pendingConflictStore.write(conflictSet)
                            runtime.stateHolder.state.value = WebDavSyncState.ConflictDetected(conflictSet)
                        }

                        val execution =
                            applyActionsConcurrently(
                                allNormalActions,
                                client,
                                layout,
                                verified.localFiles,
                                verified.remoteFiles,
                            )

                        fileBridge.persistMetadata(
                            client = client,
                            layout = layout,
                            localFiles = verified.localFiles,
                            remoteFiles = verified.remoteFiles,
                            actionOutcomes = execution.actionOutcomes + autoResolution.autoResolvedOutcomes,
                            localChanged = execution.localChanged || autoResolution.localChanged,
                            remoteChanged = execution.remoteChanged || autoResolution.remoteChanged,
                            unresolvedPaths =
                                conflictSet
                                    ?.files
                                    ?.mapTo(linkedSetOf(), SyncConflictFile::relativePath)
                                    .orEmpty(),
                            completeSnapshot = conflictSet == null,
                        )

                        val effectivePlan =
                            verified.plan.copy(
                                actions = allNormalActions + autoResolution.unresolvedConflictActions,
                            )
                        WebDavSyncExecutionResult(
                            syncResult = buildSyncResult(effectivePlan, conflictSet, execution),
                            memoRefreshPlan = execution.memoRefreshPlan.merge(autoResolution.memoRefreshPlan),
                        )
                    }
                refreshAfterSync(executionResult.syncResult, executionResult.memoRefreshPlan)
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
            val metadataByPath = runtime.metadataDao.getAll().associateBy { it.relativePath }

            val classification =
                classifyWebDavInitialOverlaps(
                    localFiles = localFiles,
                    remoteFiles = remoteFiles,
                    metadataByPath = metadataByPath,
                    client = client,
                    layout = layout,
                    fileBridge = fileBridge,
                )
            if (classification.equivalentMetadataByPath.isNotEmpty()) {
                runtime.metadataDao.upsertAll(classification.equivalentMetadataByPath.values.toList())
            }

            val effectiveMetadata = metadataByPath + classification.equivalentMetadataByPath
            val plan =
                runtime.planner.plan(
                    localFiles = localFiles,
                    remoteFiles = remoteFiles,
                    metadata = effectiveMetadata,
                    preResolvedActionsByPath = classification.resolvedActionsByPath,
                )
            return PreparedWebDavSync(
                localFiles = localFiles,
                remoteFiles = remoteFiles,
                plan = plan,
                metadataByPath = effectiveMetadata,
                isInitialSync = metadataByPath.isEmpty(),
            )
        }

        private suspend fun autoResolveConflicts(
            conflictActions: List<WebDavSyncAction>,
            client: WebDavClient,
            layout: SyncDirectoryLayout,
            localFiles: Map<String, LocalWebDavFile>,
            remoteFiles: Map<String, RemoteWebDavFile>,
        ): WebDavAutoConflictResolution {
            if (conflictActions.isEmpty()) {
                return WebDavAutoConflictResolution()
            }

            val conflictFiles =
                buildConflictFiles(conflictActions, client, layout, localFiles, remoteFiles)

            val resolvedActions = mutableListOf<WebDavSyncAction>()
            val unresolvedFiles = mutableListOf<SyncConflictFile>()
            val unresolvedConflictActions = mutableListOf<WebDavSyncAction>()
            val autoResolvedOutcomes =
                mutableMapOf<String, Pair<WebDavSyncDirection, WebDavSyncReason>>()
            var localChanged = false
            var remoteChanged = false
            var memoRefreshPlan: WebDavMemoRefreshPlan = WebDavMemoRefreshPlan.None

            fun markUnresolved(file: SyncConflictFile) {
                unresolvedFiles += file
                unresolvedConflictActions +=
                    WebDavSyncAction(
                        file.relativePath,
                        WebDavSyncDirection.CONFLICT,
                        WebDavSyncReason.CONFLICT,
                    )
            }

            conflictFiles.forEach { file ->
                val choice = SyncConflictAutoResolutionAdvisor.safeAutoResolutionChoice(file)
                when (choice) {
                    SyncConflictResolutionChoice.KEEP_LOCAL -> {
                        resolvedActions +=
                            WebDavSyncAction(
                                file.relativePath,
                                WebDavSyncDirection.UPLOAD,
                                WebDavSyncReason.LOCAL_NEWER,
                            )
                    }

                    SyncConflictResolutionChoice.KEEP_REMOTE -> {
                        resolvedActions +=
                            WebDavSyncAction(
                                file.relativePath,
                                WebDavSyncDirection.DOWNLOAD,
                                WebDavSyncReason.REMOTE_NEWER,
                            )
                    }

                    SyncConflictResolutionChoice.MERGE_TEXT -> {
                        val merged = SyncConflictAutoResolutionAdvisor.mergedText(file)
                        if (merged != null && fileBridge.isMemoPath(file.relativePath, layout)) {
                            applyMergedConflictResolution(file, merged, client, layout)
                            autoResolvedOutcomes[file.relativePath] =
                                WebDavSyncDirection.UPLOAD to WebDavSyncReason.LOCAL_NEWER
                            localChanged = true
                            remoteChanged = true
                            val target = resolveMemoRefreshTarget(file.relativePath, layout)
                            if (target != null) {
                                memoRefreshPlan = memoRefreshPlan.merge(WebDavMemoRefreshPlan.Targets(target))
                            }
                        } else {
                            markUnresolved(file)
                        }
                    }

                    SyncConflictResolutionChoice.SKIP_FOR_NOW, null -> markUnresolved(file)
                }
            }

            return WebDavAutoConflictResolution(
                resolvedActions = resolvedActions,
                unresolvedFiles = unresolvedFiles,
                unresolvedConflictActions = unresolvedConflictActions,
                autoResolvedOutcomes = autoResolvedOutcomes,
                localChanged = localChanged,
                remoteChanged = remoteChanged,
                memoRefreshPlan = memoRefreshPlan,
            )
        }

        private suspend fun applyMergedConflictResolution(
            file: SyncConflictFile,
            mergedContent: String,
            client: WebDavClient,
            layout: SyncDirectoryLayout,
        ) {
            runtime.markdownStorageDataSource.saveFileIn(
                directory = MemoDirectoryType.MAIN,
                filename = fileBridge.extractMemoFilename(file.relativePath, layout),
                content = mergedContent,
            )
            client.put(
                path = file.relativePath,
                bytes = mergedContent.toByteArray(StandardCharsets.UTF_8),
                contentType = fileBridge.contentTypeForPath(file.relativePath, layout),
            )
        }

        private suspend fun buildConflictFiles(
            actions: List<WebDavSyncAction>,
            client: WebDavClient,
            layout: SyncDirectoryLayout,
            localFiles: Map<String, LocalWebDavFile>,
            remoteFiles: Map<String, RemoteWebDavFile>,
        ): List<SyncConflictFile> =
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
                    if (fileBridge.isMemoPath(action.path, layout)) {
                        try {
                            String(client.get(action.path).bytes, StandardCharsets.UTF_8)
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                SyncConflictFile(
                    relativePath = action.path,
                    localContent = localContent,
                    remoteContent = remoteContent,
                    isBinary = !action.path.endsWith(WEBDAV_MEMO_SUFFIX),
                    localLastModified = localFiles[action.path]?.lastModified,
                    remoteLastModified = remoteFiles[action.path]?.lastModified,
                )
            }

        private suspend fun applyActionsConcurrently(
            actions: List<WebDavSyncAction>,
            client: WebDavClient,
            layout: SyncDirectoryLayout,
            localFiles: Map<String, LocalWebDavFile>,
            remoteFiles: Map<String, RemoteWebDavFile>,
        ): ActionExecutionResult {
            if (actions.isEmpty()) {
                return ActionExecutionResult(
                    actionOutcomes = emptyMap(),
                    failedPaths = emptyList(),
                    localChanged = false,
                    remoteChanged = false,
                    memoRefreshPlan = WebDavMemoRefreshPlan.None,
                )
            }

            val concurrencyLimiter = Semaphore(WEBDAV_ACTION_CONCURRENCY)
            val indexedResults =
                coroutineScope {
                    actions.mapIndexed { index, action ->
                        async {
                            concurrencyLimiter.withPermit {
                                IndexedActionResult(
                                    index = index,
                                    action = action,
                                    state =
                                        actionApplier.applyAction(
                                            action = action,
                                            client = client,
                                            layout = layout,
                                            localFiles = localFiles,
                                            remoteFiles = remoteFiles,
                                        ),
                                )
                            }
                        }
                    }.awaitAll().sortedBy(IndexedActionResult::index)
                }

            val actionOutcomes = mutableMapOf<String, Pair<WebDavSyncDirection, WebDavSyncReason>>()
            val failedPaths = mutableListOf<String>()
            var localChanged = false
            var remoteChanged = false
            var memoRefreshPlan: WebDavMemoRefreshPlan = WebDavMemoRefreshPlan.None

            indexedResults.forEach { execution ->
                when (val result = execution.state) {
                    ActionExecutionState.Skipped -> Unit
                    is ActionExecutionState.Applied -> {
                        localChanged = localChanged || result.localChanged
                        remoteChanged = remoteChanged || result.remoteChanged
                        actionOutcomes[execution.action.path] =
                            execution.action.direction to execution.action.reason
                        val target = resolveMemoRefreshTarget(execution.action.path, layout)
                        if (target != null && result.localChanged) {
                            memoRefreshPlan = memoRefreshPlan.merge(WebDavMemoRefreshPlan.Targets(target))
                        }
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
                memoRefreshPlan = memoRefreshPlan,
            )
        }

        private suspend fun refreshAfterSync(
            result: WebDavSyncResult,
            memoRefreshPlan: WebDavMemoRefreshPlan,
        ): WebDavSyncResult {
            if (!result.shouldRefreshMemoCache()) {
                return result
            }

            return runNonFatalCatching {
                when (memoRefreshPlan) {
                    WebDavMemoRefreshPlan.None -> Unit
                    WebDavMemoRefreshPlan.Full ->
                        runtime.memoSynchronizer.refreshImportedSync()

                    is WebDavMemoRefreshPlan.Targets ->
                        memoRefreshPlan.filenames.sorted().forEach { targetFilename ->
                            runtime.memoSynchronizer.refreshImportedSync(targetFilename)
                        }
                }
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

        private fun resolveMemoRefreshTarget(
            path: String,
            layout: SyncDirectoryLayout,
        ): String? =
            if (fileBridge.isMemoPath(path, layout)) {
                fileBridge.extractMemoFilename(path, layout)
            } else {
                null
            }

        private fun buildSyncResult(
            plan: WebDavSyncPlan,
            conflictSet: SyncConflictSet?,
            execution: ActionExecutionResult,
        ): WebDavSyncResult =
            when {
                execution.failedPaths.isNotEmpty() -> {
                    val summary =
                        "WebDAV sync partially failed: ${execution.failedPaths.size} " +
                            "file(s) failed: ${execution.failedPaths.joinToString()}"
                    WebDavSyncResult.Error(
                        message = summary,
                        outcomes = plan.actions.map(WebDavSyncAction::toOutcome),
                    )
                }

                conflictSet != null ->
                    WebDavSyncResult.Conflict(
                        message = "${conflictSet.files.size} conflicting file(s) detected",
                        conflicts = conflictSet,
                    )

                plan.actions.isEmpty() ->
                    WebDavSyncResult.Success(
                        message = "WebDAV already up to date",
                        outcomes = plan.actions.map(WebDavSyncAction::toOutcome),
                    )

                else ->
                    WebDavSyncResult.Success(
                        message = "WebDAV sync completed",
                        outcomes = plan.actions.map(WebDavSyncAction::toOutcome),
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
            remoteFiles: Map<String, RemoteWebDavFile> = emptyMap(),
        ): ActionExecutionState =
            runNonFatalCatching {
                when (action.direction) {
                    WebDavSyncDirection.UPLOAD ->
                        uploadAction(action, client, layout, localFiles, remoteFiles)
                    WebDavSyncDirection.DOWNLOAD -> downloadAction(action, client, layout)
                    WebDavSyncDirection.DELETE_LOCAL -> deleteLocalAction(action, layout)
                    WebDavSyncDirection.DELETE_REMOTE -> deleteRemoteAction(action, client, remoteFiles)
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
            remoteFiles: Map<String, RemoteWebDavFile>,
        ): ActionExecutionState {
            runtime.stateHolder.state.value = WebDavSyncState.Uploading
            val bytes = loadUploadBytes(action, layout) ?: return ActionExecutionState.Skipped
            val remoteFile = remoteFiles[action.path]
            client.put(
                path = action.path,
                bytes = bytes,
                contentType = fileBridge.contentTypeForPath(action.path, layout),
                lastModifiedHint = localFiles[action.path]?.lastModified,
                expectedEtag = remoteFile?.etag,
                requireAbsent = remoteFile == null,
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
            remoteFiles: Map<String, RemoteWebDavFile>,
        ): ActionExecutionState {
            runtime.stateHolder.state.value = WebDavSyncState.Deleting
            client.delete(
                path = action.path,
                expectedEtag = remoteFiles[action.path]?.etag,
            )
            return ActionExecutionState.Applied(localChanged = false, remoteChanged = true)
        }
    }

private data class PreparedWebDavSync(
    val localFiles: Map<String, LocalWebDavFile>,
    val remoteFiles: Map<String, RemoteWebDavFile>,
    val metadataByPath: Map<String, com.lomo.data.local.entity.WebDavSyncMetadataEntity>,
    val plan: WebDavSyncPlan,
    val isInitialSync: Boolean = false,
)

private class WebDavPreparedActionVerificationGate {
    suspend fun verify(
        prepared: PreparedWebDavSync,
        client: WebDavClient,
        fileBridge: WebDavSyncFileBridge,
    ): PreparedWebDavSync {
        val candidatePaths =
            prepared.plan.actions
                .asSequence()
                .filter(::requiresVerification)
                .map(WebDavSyncAction::path)
                .toSortedSet()
        if (candidatePaths.isEmpty()) {
            return prepared
        }
        val refreshedRemoteFiles = prepared.remoteFiles.toMutableMap()
        val refreshedFolders =
            candidatePaths
                .map(::parentFolderPath)
                .distinct()
                .associateWith { folderPath ->
                    fileBridge.remoteFilesInFolder(client, folderPath)
                }
        candidatePaths.forEach { path ->
            val refreshedRemote = refreshedFolders[parentFolderPath(path)]?.get(path)
            if (refreshedRemote == null) {
                refreshedRemoteFiles.remove(path)
            } else {
                refreshedRemoteFiles[path] = refreshedRemote
            }
        }
        val replannedActions =
            runtimePlan(
                prepared = prepared,
                refreshedRemoteFiles = refreshedRemoteFiles,
                candidatePaths = candidatePaths,
            )
        return prepared.copy(
            remoteFiles = refreshedRemoteFiles,
            plan = replannedActions,
        )
    }

    private fun runtimePlan(
        prepared: PreparedWebDavSync,
        refreshedRemoteFiles: Map<String, RemoteWebDavFile>,
        candidatePaths: Set<String>,
    ): WebDavSyncPlan {
        val planner = WebDavSyncPlanner()
        val replannedByPath =
            planner.plan(
                localFiles = prepared.localFiles.filterKeys(candidatePaths::contains),
                remoteFiles = refreshedRemoteFiles.filterKeys(candidatePaths::contains),
                metadata = prepared.metadataByPath.filterKeys(candidatePaths::contains),
            ).actions.associateBy(WebDavSyncAction::path)
        val mergedActions =
            prepared.plan.actions
                .asSequence()
                .filterNot { action -> action.path in candidatePaths }
                .plus(candidatePaths.mapNotNull(replannedByPath::get))
                .sortedBy(WebDavSyncAction::path)
                .toList()
        return WebDavSyncPlan(
            actions = mergedActions,
            pendingChanges = mergedActions.count { action -> action.direction != WebDavSyncDirection.NONE },
        )
    }

    private fun requiresVerification(action: WebDavSyncAction): Boolean =
        action.direction == WebDavSyncDirection.DELETE_LOCAL ||
            action.direction == WebDavSyncDirection.DELETE_REMOTE

    private fun parentFolderPath(path: String): String = path.substringBeforeLast('/', "")
}

private data class ActionExecutionResult(
    val actionOutcomes: Map<String, Pair<WebDavSyncDirection, WebDavSyncReason>>,
    val failedPaths: List<String>,
    val localChanged: Boolean,
    val remoteChanged: Boolean,
    val memoRefreshPlan: WebDavMemoRefreshPlan = WebDavMemoRefreshPlan.None,
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

private data class IndexedActionResult(
    val index: Int,
    val action: WebDavSyncAction,
    val state: ActionExecutionState,
)

private data class WebDavSyncExecutionResult(
    val syncResult: WebDavSyncResult,
    val memoRefreshPlan: WebDavMemoRefreshPlan,
)

private data class WebDavAutoConflictResolution(
    val resolvedActions: List<WebDavSyncAction> = emptyList(),
    val unresolvedFiles: List<SyncConflictFile> = emptyList(),
    val unresolvedConflictActions: List<WebDavSyncAction> = emptyList(),
    val autoResolvedOutcomes: Map<String, Pair<WebDavSyncDirection, WebDavSyncReason>> = emptyMap(),
    val localChanged: Boolean = false,
    val remoteChanged: Boolean = false,
    val memoRefreshPlan: WebDavMemoRefreshPlan = WebDavMemoRefreshPlan.None,
)

internal sealed interface WebDavMemoRefreshPlan {
    data object None : WebDavMemoRefreshPlan

    data class Targets(
        val filenames: Set<String>,
    ) : WebDavMemoRefreshPlan {
        constructor(filename: String) : this(setOf(filename))
    }

    data object Full : WebDavMemoRefreshPlan
}

internal fun WebDavMemoRefreshPlan.merge(other: WebDavMemoRefreshPlan): WebDavMemoRefreshPlan =
    when {
        this == WebDavMemoRefreshPlan.Full || other == WebDavMemoRefreshPlan.Full ->
            WebDavMemoRefreshPlan.Full

        this is WebDavMemoRefreshPlan.Targets && other is WebDavMemoRefreshPlan.Targets ->
            WebDavMemoRefreshPlan.Targets(this.filenames + other.filenames)

        this is WebDavMemoRefreshPlan.Targets -> this
        other is WebDavMemoRefreshPlan.Targets -> other
        else -> WebDavMemoRefreshPlan.None
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

private const val WEBDAV_ACTION_CONCURRENCY = 4
