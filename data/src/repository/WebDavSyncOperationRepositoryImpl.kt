package com.lomo.data.repository
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.sync.SyncLayoutMigration
import com.lomo.data.util.sanitizePathForLog
import com.lomo.data.util.runNonFatalCatching
import com.lomo.data.webdav.WebDavClient
import com.lomo.data.local.entity.WebDavSyncMetadataEntity
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictAutoResolutionAdvisor
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.model.toInitialImportReview
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
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.io.path.createTempFile
class WebDavSyncOperationRepositoryImpl
constructor(
        private val syncExecutor: WebDavSyncExecutor,
        private val statusTester: WebDavSyncStatusTester,
        private val stateHolder: WebDavSyncStateHolder,
        private val pendingConflictStore: PendingSyncConflictStore,
    ) : WebDavSyncOperationRepository {
        private val syncExecutionGate =
            SyncExecutionGate<WebDavSyncResult>(
                defaultInProgressResult = { WebDavSyncResult.Success("WebDAV sync already in progress") },
            )
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
        ): WebDavSyncResult =
            syncExecutionGate.run(
                inProgressResult = { WebDavSyncResult.Success(inProgressMessage) },
                block = block,
            )
        private suspend fun restorePendingConflictIfPresent(): WebDavSyncResult? {
            return restorePendingConflict(
                pendingConflictStore = pendingConflictStore,
                backendType = SyncBackendType.WEBDAV,
                restorer = syncExecutor::restorePendingConflict,
                onRestored = { pending -> stateHolder.state.value = WebDavSyncState.ConflictDetected(pending) },
                asResult = { pending -> WebDavSyncResult.Conflict("Pending conflicts remain", pending) },
                asInvalidatedResult = { reason ->
                    WebDavSyncResult.Error("Pending WebDAV conflict session requires rebuild: $reason")
                },
                asFailedResult = { error ->
                    WebDavSyncResult.Error(
                        message = "Pending WebDAV conflict session restore failed: ${error.category}",
                        exception = error.cause,
                    )
                },
            )
        }
        private suspend fun clearPendingConflictsOnSuccess(result: WebDavSyncResult) {
            clearPendingConflictOnSuccess(
                pendingConflictStore = pendingConflictStore,
                backendType = SyncBackendType.WEBDAV,
                result = result,
                isSuccess = { candidate -> candidate is WebDavSyncResult.Success },
            )
        }
    }
class WebDavSyncStatusTester
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
                val plan =
                    runtime.planner.plan(
                        localFiles = localFiles.toWebDavRemoteSyncLocalSnapshots(),
                        remoteFiles = remoteFiles.toWebDavRemoteSyncRemoteSnapshots(),
                        metadata = metadata.toWebDavRemoteSyncMetadataSnapshots(),
                    )
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
class WebDavSyncExecutor
    internal constructor(
        private val runtime: WebDavSyncRepositoryContext,
        private val support: WebDavSyncRepositorySupport,
        private val fileBridge: WebDavSyncFileBridge,
        private val actionApplier: WebDavSyncActionApplier,
        private val lifecycleRunner: RemoteSyncLifecycleRunner,
        private val localChangeJournalStore: WebDavLocalChangeJournalStore,
        private val pendingConflictStore: PendingSyncConflictStore,
        private val pendingReviewStore: PendingSyncReviewStore,
    ) {
        private val verificationGate = WebDavPreparedActionVerificationGate(runtime.performanceTuner)
        private val snapshotLoader =
            WebDavSyncSnapshotLoader(
                runtime = runtime,
                fileBridge = fileBridge,
                localChangeJournalStore = localChangeJournalStore,
            )
        private val pendingConflictRestorer =
            WebDavPendingConflictSessionRestorer(
                runtime = runtime,
                support = support,
                fileBridge = fileBridge,
                lifecycleRunner = lifecycleRunner,
            )
        suspend fun performSync(): WebDavSyncResult {
            val config = support.resolveConfig() ?: return support.notConfiguredResult()
            val layout = SyncDirectoryLayout.resolve(runtime.dataStore)
            return support.runWebDavIo {
                val client = support.createClient(config)
                lifecycleRunner.run(WebDavSyncLifecycleStages(client = client, layout = layout))
            }
        }
        suspend fun restorePendingConflict(
            descriptor: PendingSyncConflictDescriptor,
        ): PendingSyncRestoreResult<SyncConflictSet> = pendingConflictRestorer.restore(descriptor)
        private inner class WebDavSyncLifecycleStages(
            private val client: WebDavClient,
            private val layout: SyncDirectoryLayout,
        ) : RemoteSyncLifecycleStages<
                WebDavSyncSnapshot,
                PreparedWebDavSync,
                PreparedWebDavSync,
                WebDavConflictMaterialization,
                ActionExecutionResult,
                WebDavCommittedSync,
                WebDavSyncResult,
                WebDavSyncResult,
            > {
            override val context: RemoteSyncLifecycleContext =
                RemoteSyncLifecycleContext(
                    backend = SyncBackendType.WEBDAV,
                    budget = RemoteSyncBudgetPolicy.Limited(DEFAULT_REMOTE_SYNC_NETWORK_OPERATION_BUDGET),
                )
            private lateinit var meteredClient: WebDavClient
            override suspend fun loadSnapshot(session: RemoteSyncLifecycleSession): WebDavSyncSnapshot {
                meteredClient = session.meter(client)
                return snapshotLoader.loadSyncSnapshot(meteredClient, layout)
            }
            override suspend fun plan(
                snapshot: WebDavSyncSnapshot,
                session: RemoteSyncLifecycleSession,
            ): PreparedWebDavSync = snapshotLoader.planSync(snapshot)
            override suspend fun verify(
                plan: PreparedWebDavSync,
                session: RemoteSyncLifecycleSession,
            ): PreparedWebDavSync =
                verificationGate.verify(plan, meteredClient, fileBridge)
            override suspend fun materializeConflicts(
                verified: PreparedWebDavSync,
                session: RemoteSyncLifecycleSession,
            ): WebDavConflictMaterialization =
                materializeVerifiedConflicts(verified, meteredClient, layout)
            override suspend fun apply(
                verified: PreparedWebDavSync,
                conflicts: WebDavConflictMaterialization,
                session: RemoteSyncLifecycleSession,
            ): ActionExecutionResult =
                applyActionsConcurrently(
                    conflicts.normalActions,
                    meteredClient,
                    layout,
                    verified.localFiles,
                    verified.remoteFiles,
                )
            override suspend fun commitMetadata(
                verified: PreparedWebDavSync,
                conflicts: WebDavConflictMaterialization,
                applied: ActionExecutionResult,
                session: RemoteSyncLifecycleSession,
            ): WebDavCommittedSync =
                commitVerifiedMetadata(
                    verified = verified,
                    conflicts = conflicts,
                    execution = applied,
                    client = meteredClient,
                    layout = layout,
                )
            override suspend fun finalize(
                verified: PreparedWebDavSync,
                conflicts: WebDavConflictMaterialization,
                applied: ActionExecutionResult,
                metadata: WebDavCommittedSync,
                session: RemoteSyncLifecycleSession,
            ): WebDavSyncResult = refreshAfterSync(metadata.result, metadata.memoRefreshPlan)
            override fun summarizeSnapshot(snapshot: WebDavSyncSnapshot): RemoteSyncSnapshotTelemetry =
                RemoteSyncSnapshotTelemetry(
                    localFileCount = snapshot.localFiles.size,
                    remoteFileCount = snapshot.remoteFiles.size,
                    metadataEntryCount = snapshot.metadataByPath.size,
                )
            override fun summarizePlan(plan: PreparedWebDavSync): RemoteSyncActionTelemetry =
                plan.plan.actions.toRemoteSyncActionTelemetry()
            override fun summarizeVerification(verified: PreparedWebDavSync): RemoteSyncActionTelemetry =
                verified.plan.actions.toRemoteSyncActionTelemetry()
            override fun summarizeRefresh(finalized: WebDavSyncResult): RemoteSyncRefreshTelemetry =
                RemoteSyncRefreshTelemetry(durationMillis = 0)
            override fun mapResult(finalized: WebDavSyncResult): WebDavSyncResult = finalized
            override fun mapError(error: Throwable): WebDavSyncResult = support.mapError(error)
            override suspend fun release() = Unit
        }
        private suspend fun materializeVerifiedConflicts(
            verified: PreparedWebDavSync,
            client: WebDavClient,
            layout: SyncDirectoryLayout,
        ): WebDavConflictMaterialization {
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
            val unresolvedConflictSet =
                autoResolution.unresolvedFiles
                    .takeIf(List<SyncConflictFile>::isNotEmpty)
                    ?.let { files ->
                        SyncConflictSet(
                            source = SyncBackendType.WEBDAV,
                            files = files,
                            timestamp = System.currentTimeMillis(),
                        )
                    }
            val reviewSession =
                unresolvedConflictSet
                    ?.takeIf { verified.isInitialSync }
                    ?.toInitialImportReview()
            val conflictSet = unresolvedConflictSet.takeIf { reviewSession == null }
            if (conflictSet != null) {
                pendingConflictStore.write(conflictSet)
                runtime.stateHolder.state.value = WebDavSyncState.ConflictDetected(conflictSet)
            }
            if (reviewSession != null) {
                pendingReviewStore.write(reviewSession)
                runtime.stateHolder.state.value = WebDavSyncState.ReviewingInitialSync(reviewSession)
            }
            return WebDavConflictMaterialization(
                conflictSet = conflictSet,
                reviewSession = reviewSession,
                autoResolution = autoResolution,
                normalActions = normalActions + autoResolution.resolvedActions,
                effectivePlan =
                    verified.plan.copy(
                        actions =
                            normalActions +
                                autoResolution.resolvedActions +
                                autoResolution.unresolvedConflictActions,
                    ),
            )
        }
        private suspend fun commitVerifiedMetadata(
            verified: PreparedWebDavSync,
            conflicts: WebDavConflictMaterialization,
            execution: ActionExecutionResult,
            client: WebDavClient,
            layout: SyncDirectoryLayout,
        ): WebDavCommittedSync {
            fileBridge.persistMetadata(
                client = client,
                layout = layout,
                localFiles = verified.localFiles,
                remoteFiles = verified.remoteFiles,
                actionOutcomes = execution.actionOutcomes + conflicts.autoResolution.autoResolvedOutcomes,
                localChanged = execution.localChanged || conflicts.autoResolution.localChanged,
                remoteChanged = execution.remoteChanged || conflicts.autoResolution.remoteChanged,
                unresolvedPaths =
                    conflicts.conflictSet
                        ?.files
                        ?.mapTo(linkedSetOf(), SyncConflictFile::relativePath)
                        .orEmpty(),
                completeSnapshot = verified.completeSnapshot && conflicts.conflictSet == null,
            )
            if (conflicts.conflictSet == null && execution.failedPaths.isEmpty()) {
                localChangeJournalStore.remove(verified.consumedJournalIds)
            }
            return WebDavCommittedSync(
                result =
                    buildSyncResult(
                        plan = conflicts.effectivePlan,
                        conflictSet = conflicts.conflictSet,
                        reviewSession = conflicts.reviewSession,
                        execution = execution,
                    ),
                memoRefreshPlan = execution.memoRefreshPlan.merge(conflicts.autoResolution.memoRefreshPlan),
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
                        if (merged != null && isWebDavMemoPath(file.relativePath, layout)) {
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
                filename = extractWebDavMemoFilename(file.relativePath, layout),
                content = mergedContent,
            )
            client.putSmallFile(
                path = file.relativePath,
                bytes = mergedContent.toByteArray(StandardCharsets.UTF_8),
                contentType = webDavContentTypeForPath(file.relativePath, layout, runtime),
            )
        }
        private suspend fun buildConflictFiles(
            actions: List<WebDavSyncAction>,
            client: WebDavClient,
            layout: SyncDirectoryLayout,
            localFiles: Map<String, LocalWebDavFile>,
            remoteFiles: Map<String, RemoteWebDavFile>,
        ): List<SyncConflictFile> {
            val conflictLimiter =
                Semaphore(runtime.performanceTuner.currentProfile().webDavActionConcurrency.coercePositiveConcurrency())
            return coroutineScope {
                actions.map { action ->
                    async {
                        conflictLimiter.withPermit {
                            val isMemo = isWebDavMemoPath(action.path, layout)
                            val localContent =
                                if (isMemo) {
                                    runtime.markdownStorageDataSource.readFileIn(
                                        MemoDirectoryType.MAIN,
                                        extractWebDavMemoFilename(action.path, layout),
                                    )
                                } else {
                                    null
                                }
                            val remoteContent =
                                if (isMemo) {
                                    try {
                                        String(client.getSmallFile(action.path).bytes, StandardCharsets.UTF_8)
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
                    }
                }.awaitAll()
            }
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
            val concurrencyLimiter =
                Semaphore(runtime.performanceTuner.currentProfile().webDavActionConcurrency.coercePositiveConcurrency())
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
                    is WebDavMemoRefreshPlan.Targets -> {
                        val refreshConcurrency =
                            runtime.performanceTuner
                                .currentProfile()
                                .webDavActionConcurrency
                                .coercePositiveConcurrency()
                        val refreshLimiter =
                            Semaphore(refreshConcurrency)
                        coroutineScope {
                            memoRefreshPlan.filenames.sorted().map { targetFilename ->
                                async {
                                    refreshLimiter.withPermit {
                                        runtime.memoSynchronizer.refreshImportedSync(targetFilename)
                                    }
                                }
                            }.awaitAll()
                        }
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
            if (isWebDavMemoPath(path, layout)) {
                extractWebDavMemoFilename(path, layout)
            } else {
                null
            }
        private fun buildSyncResult(
            plan: WebDavSyncPlan,
            conflictSet: SyncConflictSet?,
            reviewSession: SyncReviewSession?,
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
                reviewSession != null ->
                    WebDavSyncResult.Review(
                        message = "${reviewSession.items.size} file(s) require import review",
                        review = reviewSession,
                    )
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
class WebDavSyncActionApplier
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
                Timber.e(error, "Failed to %s %s", action.operationName(), sanitizePathForLog(action.path))
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
            val remoteFile = remoteFiles[action.path]
            val contentType = webDavContentTypeForPath(action.path, layout, runtime)
            if (isWebDavMemoPath(action.path, layout)) {
                val bytes = loadMemoUploadBytes(action, layout) ?: return ActionExecutionState.Skipped
                client.putSmallFile(
                    path = action.path,
                    bytes = bytes,
                    contentType = contentType,
                    lastModifiedHint = localFiles[action.path]?.lastModified,
                    expectedEtag = remoteFile?.etag,
                    requireAbsent = remoteFile == null,
                )
            } else {
                withTempFile(action.path.transferSuffix()) { transferFile ->
                    runtime.localMediaSyncStore.exportToFile(action.path, layout, transferFile)
                    client.putFile(
                        path = action.path,
                        file = transferFile,
                        contentType = contentType,
                        lastModifiedHint = localFiles[action.path]?.lastModified,
                        expectedEtag = remoteFile?.etag,
                        requireAbsent = remoteFile == null,
                    )
                }
            }
            return ActionExecutionState.Applied(localChanged = false, remoteChanged = true)
        }
        private suspend fun loadMemoUploadBytes(
            action: WebDavSyncAction,
            layout: SyncDirectoryLayout,
        ): ByteArray? =
            run {
                val content =
                    runtime.markdownStorageDataSource.readFileIn(
                        MemoDirectoryType.MAIN,
                        extractWebDavMemoFilename(action.path, layout),
                    )
                if (content == null) {
                    Timber.w("Local memo missing during upload: %s, skipping", sanitizePathForLog(action.path))
                    null
                } else {
                    content.toByteArray(StandardCharsets.UTF_8)
                }
            }
        private suspend fun downloadAction(
            action: WebDavSyncAction,
            client: WebDavClient,
            layout: SyncDirectoryLayout,
        ): ActionExecutionState {
            runtime.stateHolder.state.value = WebDavSyncState.Downloading
            if (isWebDavMemoPath(action.path, layout)) {
                val remoteFile = client.getSmallFile(action.path)
                runtime.markdownStorageDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = extractWebDavMemoFilename(action.path, layout),
                    content = String(remoteFile.bytes, StandardCharsets.UTF_8),
                )
            } else {
                withTempFile(action.path.transferSuffix()) { transferFile ->
                    client.getToFile(action.path, transferFile)
                    runtime.localMediaSyncStore.importFromFile(action.path, transferFile, layout)
                }
            }
            return ActionExecutionState.Applied(localChanged = true, remoteChanged = false)
        }
        private suspend fun deleteLocalAction(
            action: WebDavSyncAction,
            layout: SyncDirectoryLayout,
        ): ActionExecutionState {
            runtime.stateHolder.state.value = WebDavSyncState.Deleting
            if (isWebDavMemoPath(action.path, layout)) {
                runtime.markdownStorageDataSource.deleteFileIn(
                    MemoDirectoryType.MAIN,
                    extractWebDavMemoFilename(action.path, layout),
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
        private suspend fun <T> withTempFile(
            suffix: String,
            block: suspend (File) -> T,
        ): T {
            val file = createTempFile(prefix = "webdav-sync-", suffix = suffix).toFile()
            return try {
                block(file)
            } finally {
                file.delete()
            }
        }
    }
private fun String.transferSuffix(): String =
    substringAfterLast('.', "").takeIf(String::isNotBlank)?.let { ".$it" } ?: ".tmp"
private class WebDavSyncSnapshotLoader(
    private val runtime: WebDavSyncRepositoryContext,
    private val fileBridge: WebDavSyncFileBridge,
    private val localChangeJournalStore: WebDavLocalChangeJournalStore,
) {
    suspend fun loadSyncSnapshot(
        client: WebDavClient,
        layout: SyncDirectoryLayout,
    ): WebDavSyncSnapshot {
        val profile = runtime.performanceTuner.currentProfile()
        runtime.stateHolder.state.value = WebDavSyncState.Initializing
        fileBridge.ensureRemoteDirectories(client, layout)
        SyncLayoutMigration.migrateWebDavRemote(client, layout)
        runtime.stateHolder.state.value = WebDavSyncState.Listing
        val metadataByPath = runtime.metadataDao.getAll().associateBy { it.relativePath }
        val incremental =
            tryPrepareIncrementalSync(
                client = client,
                layout = layout,
                metadataByPath = metadataByPath,
            )
        val localFiles = incremental?.localFiles ?: fileBridge.localFiles(layout)
        val remoteFiles =
            incremental?.remoteFiles
                ?: runNonFatalCatching {
                    fileBridge.remoteFiles(client, layout)
                }.getOrElse { error ->
                    throw IllegalStateException(
                        "Failed to list remote WebDAV files: ${error.message ?: WEBDAV_UNKNOWN_ERROR_MESSAGE}",
                        error,
                    )
                }
        val classification =
            classifyWebDavInitialOverlaps(
                localFiles = localFiles,
                remoteFiles = remoteFiles,
                metadataByPath = metadataByPath,
                client = client,
                layout = layout,
                overlapConcurrency = profile.webDavInitialOverlapConcurrency.coercePositiveConcurrency(),
            )
        if (classification.equivalentMetadataByPath.isNotEmpty()) {
            runtime.metadataDao.upsertAll(classification.equivalentMetadataByPath.values.toList())
        }
        val effectiveMetadata = metadataByPath + classification.equivalentMetadataByPath
        return WebDavSyncSnapshot(
            localFiles = localFiles,
            remoteFiles = remoteFiles,
            metadataByPath = effectiveMetadata,
            preResolvedActionsByPath = classification.resolvedActionsByPath,
            isInitialSync = metadataByPath.isEmpty(),
            completeSnapshot = incremental == null,
            consumedJournalIds = incremental?.consumedJournalIds.orEmpty(),
        )
    }
    fun planSync(snapshot: WebDavSyncSnapshot): PreparedWebDavSync {
        val plan =
            runtime.planner.plan(
                localFiles = snapshot.localFiles.toWebDavRemoteSyncLocalSnapshots(),
                remoteFiles = snapshot.remoteFiles.toWebDavRemoteSyncRemoteSnapshots(),
                metadata = snapshot.metadataByPath.toWebDavRemoteSyncMetadataSnapshots(),
                preResolvedActionsByPath = snapshot.preResolvedActionsByPath.toWebDavRemoteSyncActions(),
            ).toWebDavPlan()
        return PreparedWebDavSync(
            localFiles = snapshot.localFiles,
            remoteFiles = snapshot.remoteFiles,
            metadataByPath = snapshot.metadataByPath,
            plan = plan,
            isInitialSync = snapshot.isInitialSync,
            completeSnapshot = snapshot.completeSnapshot,
            consumedJournalIds = snapshot.consumedJournalIds,
        )
    }
    private suspend fun tryPrepareIncrementalSync(
        client: WebDavClient,
        layout: SyncDirectoryLayout,
        metadataByPath: Map<String, WebDavSyncMetadataEntity>,
    ): PreparedWebDavIncrementalSnapshot? {
        if (!localChangeJournalStore.incrementalSyncEnabled || metadataByPath.isEmpty()) {
            return null
        }
        val lastSyncAt = runtime.dataStore.webDavLastSyncTime.first()
        if (lastSyncAt <= 0L || System.currentTimeMillis() - lastSyncAt > WEBDAV_INCREMENTAL_SYNC_WINDOW_MS) {
            return null
        }
        val journalEntries = localChangeJournalStore.read()
        if (journalEntries.isEmpty()) {
            return null
        }
        val touchedPaths =
            journalEntries.values
                .map { entry -> entry.relativePath(layout) }
                .toSortedSet()
        if (touchedPaths.isEmpty()) {
            localChangeJournalStore.clear()
            return null
        }
        val touchedLocalFiles = fileBridge.localFiles(layout, targetPaths = touchedPaths, pruneCache = false)
        val localFiles =
            metadataByPath.values
                .associate { entity ->
                    entity.relativePath to
                        LocalWebDavFile(
                            path = entity.relativePath,
                            lastModified = entity.localLastModified ?: 0L,
                            localFingerprint = entity.localFingerprint,
                        )
                }.toMutableMap()
        touchedPaths.forEach { path ->
            val localEntry = touchedLocalFiles[path]
            if (localEntry == null) {
                localFiles.remove(path)
            } else {
                localFiles[path] = localEntry
            }
        }
        val touchedFolders =
            touchedPaths.mapTo(linkedSetOf()) { path ->
                path.substringBeforeLast('/', "")
            }
        val untouchedRemoteFiles =
            metadataByPath.values
                .asSequence()
                .filterNot { entity ->
                    entity.relativePath.substringBeforeLast('/', "") in touchedFolders
                }
                .associate { entity ->
                    entity.relativePath to
                        RemoteWebDavFile(
                            path = entity.relativePath,
                            etag = entity.etag,
                            lastModified = entity.remoteLastModified,
                        )
                }
        val refreshedRemoteFiles =
            coroutineScope {
                val listLimiter =
                    Semaphore(
                        runtime.performanceTuner.currentProfile().webDavListConcurrency.coercePositiveConcurrency(),
                    )
                touchedFolders.map { folder ->
                    async {
                        listLimiter.withPermit {
                            fileBridge.remoteFilesInFolder(client, folder).values.toList()
                        }
                    }
                }.awaitAll().flatten()
            }.associateBy(RemoteWebDavFile::path)
        return PreparedWebDavIncrementalSnapshot(
            localFiles = localFiles,
            remoteFiles = untouchedRemoteFiles + refreshedRemoteFiles,
            consumedJournalIds = journalEntries.keys,
        )
    }
}
private data class PreparedWebDavSync(
    val localFiles: Map<String, LocalWebDavFile>,
    val remoteFiles: Map<String, RemoteWebDavFile>,
    val metadataByPath: Map<String, com.lomo.data.local.entity.WebDavSyncMetadataEntity>,
    val plan: WebDavSyncPlan,
    val isInitialSync: Boolean = false,
    val completeSnapshot: Boolean = true,
    val consumedJournalIds: Collection<String> = emptyList(),
)
private data class WebDavSyncSnapshot(
    val localFiles: Map<String, LocalWebDavFile>,
    val remoteFiles: Map<String, RemoteWebDavFile>,
    val metadataByPath: Map<String, com.lomo.data.local.entity.WebDavSyncMetadataEntity>,
    val preResolvedActionsByPath: Map<String, WebDavSyncAction>,
    val isInitialSync: Boolean,
    val completeSnapshot: Boolean,
    val consumedJournalIds: Collection<String>,
)
private data class PreparedWebDavIncrementalSnapshot(
    val localFiles: Map<String, LocalWebDavFile>,
    val remoteFiles: Map<String, RemoteWebDavFile>,
    val consumedJournalIds: Collection<String>,
)
private class WebDavPreparedActionVerificationGate(
    private val performanceTuner: SyncPerformanceTuner = DisabledSyncPerformanceTuner,
) {
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
            coroutineScope {
                val listLimiter =
                    Semaphore(performanceTuner.currentProfile().webDavListConcurrency.coercePositiveConcurrency())
                candidatePaths
                    .map(::parentFolderPath)
                    .distinct()
                    .map { folderPath ->
                        async {
                            listLimiter.withPermit {
                                folderPath to fileBridge.remoteFilesInFolder(client, folderPath, forceRefresh = true)
                            }
                        }
                    }.awaitAll().toMap()
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
                localFiles =
                    prepared.localFiles
                        .filterKeys(candidatePaths::contains)
                        .toWebDavRemoteSyncLocalSnapshots(),
                remoteFiles =
                    refreshedRemoteFiles
                        .filterKeys(candidatePaths::contains)
                        .toWebDavRemoteSyncRemoteSnapshots(),
                metadata =
                    prepared.metadataByPath
                        .filterKeys(candidatePaths::contains)
                        .toWebDavRemoteSyncMetadataSnapshots(),
            ).toWebDavPlan()
                .actions
                .associateBy(WebDavSyncAction::path)
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
private data class WebDavAutoConflictResolution(
    val resolvedActions: List<WebDavSyncAction> = emptyList(),
    val unresolvedFiles: List<SyncConflictFile> = emptyList(),
    val unresolvedConflictActions: List<WebDavSyncAction> = emptyList(),
    val autoResolvedOutcomes: Map<String, Pair<WebDavSyncDirection, WebDavSyncReason>> = emptyMap(),
    val localChanged: Boolean = false,
    val remoteChanged: Boolean = false,
    val memoRefreshPlan: WebDavMemoRefreshPlan = WebDavMemoRefreshPlan.None,
)
private data class WebDavConflictMaterialization(
    val conflictSet: SyncConflictSet?,
    val reviewSession: SyncReviewSession?,
    val autoResolution: WebDavAutoConflictResolution,
    val normalActions: List<WebDavSyncAction>,
    val effectivePlan: WebDavSyncPlan,
)
private data class WebDavCommittedSync(
    val result: WebDavSyncResult,
    val memoRefreshPlan: WebDavMemoRefreshPlan,
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
private fun List<WebDavSyncAction>.toRemoteSyncActionTelemetry(): RemoteSyncActionTelemetry =
    RemoteSyncActionTelemetry(
        total = size,
        upload = count { action -> action.direction == WebDavSyncDirection.UPLOAD },
        download = count { action -> action.direction == WebDavSyncDirection.DOWNLOAD },
        deleteLocal = count { action -> action.direction == WebDavSyncDirection.DELETE_LOCAL },
        deleteRemote = count { action -> action.direction == WebDavSyncDirection.DELETE_REMOTE },
        conflict = count { action -> action.direction == WebDavSyncDirection.CONFLICT },
    )
private fun WebDavSyncResult.shouldRefreshMemoCache(): Boolean =
    this is WebDavSyncResult.Success ||
        (this is WebDavSyncResult.Error && outcomes.isNotEmpty())
private fun WebDavSyncResult.stateAfterRefresh(timestamp: Long): WebDavSyncState =
    when (this) {
        is WebDavSyncResult.Success -> WebDavSyncState.Success(timestamp, message)
        is WebDavSyncResult.Error -> WebDavSyncState.Error(message, timestamp)
        is WebDavSyncResult.Conflict -> WebDavSyncState.ConflictDetected(conflicts)
        is WebDavSyncResult.Review -> WebDavSyncState.ReviewingInitialSync(review)
        WebDavSyncResult.NotConfigured -> WebDavSyncState.NotConfigured
    }
private fun WebDavSyncResult.outcomesForRefreshFailure() =
    when (this) {
        is WebDavSyncResult.Success -> outcomes
        is WebDavSyncResult.Error -> outcomes
        is WebDavSyncResult.Conflict,
        is WebDavSyncResult.Review,
        -> emptyList()
        WebDavSyncResult.NotConfigured -> emptyList()
    }
private const val WEBDAV_ACTION_CONCURRENCY = 8
private const val WEBDAV_CONFLICT_CONCURRENCY = 4
private const val WEBDAV_INCREMENTAL_SYNC_WINDOW_MS = 2 * 60_000L
