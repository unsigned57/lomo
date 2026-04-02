package com.lomo.data.repository

import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncReason
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictSet
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Singleton
class S3SyncExecutor
    @Inject
    constructor(
        private val runtime: S3SyncRepositoryContext,
        private val support: S3SyncRepositorySupport,
        private val encodingSupport: S3SyncEncodingSupport,
        private val fileBridge: S3SyncFileBridge,
        private val actionApplier: S3SyncActionApplier,
    ) {
        suspend fun performSync(): S3SyncResult {
            val config = support.resolveConfig() ?: return support.notConfiguredResult()
            val layout = com.lomo.data.sync.SyncDirectoryLayout.resolve(runtime.dataStore)
            val mode = resolveLocalSyncMode(runtime)
            val fileBridgeScope = fileBridge.modeAware(mode)
            return runNonFatalCatching {
                support.withClient(config) { client ->
                    val prepared = prepareSync(client, layout, config, fileBridgeScope, mode)
                    val execution =
                        applyActions(
                            actions = prepared.normalActions,
                            client = client,
                            layout = layout,
                            config = config,
                            localFiles = prepared.localFiles,
                            remoteFiles = prepared.remoteFiles,
                            metadataByPath = prepared.metadataByPath,
                            fileBridgeScope = fileBridgeScope,
                            mode = mode,
                        )
                    fileBridge.persistMetadata(
                        localFiles = execution.localFilesAfterSync,
                        remoteFiles = execution.remoteFilesAfterSync,
                        metadataByPath = prepared.metadataByPath,
                        actionOutcomes = execution.actionOutcomes,
                        unresolvedPaths = execution.unresolvedPaths,
                    )
                    finalizeAfterSync(buildSyncResult(prepared, execution), execution)
                }
            }.getOrElse(support::mapError)
        }

        private suspend fun prepareSync(
            client: com.lomo.data.s3.LomoS3Client,
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            config: S3ResolvedConfig,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
        ): PreparedS3Sync {
            runtime.stateHolder.state.value = S3SyncState.Initializing
            runtime.stateHolder.state.value = S3SyncState.Listing
            val (localFiles, remoteFiles, metadataByPath) =
                coroutineScope {
                    val localFilesDeferred = async { fileBridgeScope.localFiles(layout) }
                    val remoteFilesDeferred = async { fileBridgeScope.remoteFiles(client, layout, config) }
                    val metadataByPathDeferred =
                        async {
                            runtime.metadataDao.getAll().associateBy { it.relativePath }
                        }
                    Triple(
                        localFilesDeferred.await(),
                        remoteFilesDeferred.await(),
                        metadataByPathDeferred.await(),
                    )
                }
            val plan = runtime.planner.plan(localFiles, remoteFiles, metadataByPath)
            val conflictActions = plan.actions.filter { it.direction == S3SyncDirection.CONFLICT }
            val conflictSet =
                buildConflictSet(
                    conflictActions,
                    client,
                    layout,
                    config,
                    remoteFiles,
                    fileBridgeScope,
                    mode,
                )
            if (conflictSet != null) {
                runtime.stateHolder.state.value = S3SyncState.ConflictDetected(conflictSet)
            }
            return PreparedS3Sync(
                localFiles = localFiles,
                remoteFiles = remoteFiles,
                metadataByPath = metadataByPath,
                plan = plan,
                normalActions = plan.actions.filter { it.direction != S3SyncDirection.CONFLICT },
                conflictSet = conflictSet,
            )
        }

        private suspend fun buildConflictSet(
            actions: List<S3SyncAction>,
            client: com.lomo.data.s3.LomoS3Client,
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            config: S3ResolvedConfig,
            remoteFiles: Map<String, RemoteS3File>,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
        ): SyncConflictSet? {
            val concurrencyLimiter = Semaphore(S3_ACTION_CONCURRENCY)
            val conflictFiles =
                coroutineScope {
                    actions.map { action ->
                        async {
                            concurrencyLimiter.withPermit {
                                val localContent =
                                    if (isMemoPath(action.path, layout, mode)) {
                                        fileBridgeScope.readLocalText(action.path, layout)
                                    } else {
                                        null
                                    }
                                val remoteContent =
                                    runNonFatalCatching {
                                        val remotePath = remoteFiles[action.path]?.remotePath ?: return@withPermit null
                                        val payload = client.getObject(remotePath)
                                        String(
                                            encodingSupport.decodeContent(payload.bytes, config),
                                            StandardCharsets.UTF_8,
                                        )
                                    }.getOrNull()
                                SyncConflictFile(
                                    relativePath = action.path,
                                    localContent = localContent,
                                    remoteContent = remoteContent,
                                    isBinary = !action.path.endsWith(S3_MEMO_SUFFIX),
                                )
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
            return conflictFiles
                .takeIf(List<SyncConflictFile>::isNotEmpty)
                ?.let { files ->
                    SyncConflictSet(
                        source = SyncBackendType.S3,
                        files = files,
                        timestamp = System.currentTimeMillis(),
                    )
                }
        }

        private suspend fun applyActions(
            actions: List<S3SyncAction>,
            client: com.lomo.data.s3.LomoS3Client,
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            config: S3ResolvedConfig,
            localFiles: Map<String, LocalS3File>,
            remoteFiles: Map<String, RemoteS3File>,
            metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
        ): S3ActionExecutionResult =
            coroutineScope {
                val concurrencyLimiter = Semaphore(S3_ACTION_CONCURRENCY)
                val indexedResults =
                    actions.mapIndexed { index, action ->
                        async {
                            concurrencyLimiter.withPermit {
                                IndexedS3ActionExecutionResult(
                                    index = index,
                                    action = action,
                                    state =
                                        actionApplier.applyAction(
                                            action = action,
                                            client = client,
                                            layout = layout,
                                            config = config,
                                            localFiles = localFiles,
                                            remoteFiles = remoteFiles,
                                            metadataByPath = metadataByPath,
                                            fileBridgeScope = fileBridgeScope,
                                            mode = mode,
                                        ),
                                )
                            }
                        }
                    }.awaitAll().sortedBy(IndexedS3ActionExecutionResult::index)

                val actionOutcomes = mutableMapOf<String, Pair<S3SyncDirection, S3SyncReason>>()
                val failedPaths = mutableListOf<String>()
                val unresolvedPaths = mutableSetOf<String>()
                val localFilesAfterSync = localFiles.toMutableMap()
                val remoteFilesAfterSync = remoteFiles.toMutableMap()
                var localChanged = false
                var memoRefreshPlan: S3MemoRefreshPlan = S3MemoRefreshPlan.None

                indexedResults.forEach { execution ->
                    when (val result = execution.state) {
                        S3ActionExecutionState.Skipped -> {
                            unresolvedPaths += execution.action.path
                        }

                        is S3ActionExecutionState.Applied -> {
                            localChanged = localChanged || result.localChanged
                            result.updatedLocalFile?.let { updatedLocal ->
                                localFilesAfterSync[execution.action.path] = updatedLocal
                            }
                            result.deletedLocalPath?.let(localFilesAfterSync::remove)
                            result.updatedRemoteFile?.let { updatedRemote ->
                                remoteFilesAfterSync[execution.action.path] = updatedRemote
                            }
                            result.deletedRemotePath?.let(remoteFilesAfterSync::remove)
                            memoRefreshPlan = memoRefreshPlan.merge(result.memoRefreshPlan)
                            actionOutcomes[execution.action.path] =
                                execution.action.direction to execution.action.reason
                        }

                        is S3ActionExecutionState.Failed -> {
                            failedPaths += result.path
                            unresolvedPaths += execution.action.path
                        }
                    }
                }

                S3ActionExecutionResult(
                    actionOutcomes = actionOutcomes,
                    failedPaths = failedPaths,
                    unresolvedPaths = unresolvedPaths,
                    localChanged = localChanged,
                    localFilesAfterSync = localFilesAfterSync,
                    remoteFilesAfterSync = remoteFilesAfterSync,
                    memoRefreshPlan = memoRefreshPlan,
                )
            }

        private suspend fun finalizeAfterSync(
            result: S3SyncResult,
            execution: S3ActionExecutionResult?,
        ): S3SyncResult {
            if (!result.shouldFinalizeAfterSync()) {
                return result
            }
            return runNonFatalCatching {
                when (val memoRefreshPlan = execution?.memoRefreshPlan ?: S3MemoRefreshPlan.None) {
                    S3MemoRefreshPlan.None -> Unit
                    S3MemoRefreshPlan.Full -> runtime.memoSynchronizer.refresh()
                    is S3MemoRefreshPlan.Targets ->
                        memoRefreshPlan.filenames
                            .sorted()
                            .forEach { targetFilename ->
                                runtime.memoSynchronizer.refresh(targetFilename)
                            }
                }
                val now = System.currentTimeMillis()
                runtime.dataStore.updateS3LastSyncTime(now)
                runtime.stateHolder.state.value = result.stateAfterRefresh(now)
                result
            }.getOrElse { error ->
                val message =
                    "S3 sync completed but memo refresh failed: " +
                        "${error.message ?: S3_UNKNOWN_ERROR_MESSAGE}"
                runtime.stateHolder.state.value = S3SyncState.Error(message, System.currentTimeMillis())
                S3SyncResult.Error(message, error, result.outcomesForRefreshFailure())
            }
        }

        private fun buildSyncResult(
            prepared: PreparedS3Sync,
            execution: S3ActionExecutionResult,
        ): S3SyncResult =
            when {
                execution.failedPaths.isNotEmpty() -> {
                    val summary =
                        "S3 sync partially failed: ${execution.failedPaths.size} " +
                            "file(s) failed: ${execution.failedPaths.joinToString()}"
                    S3SyncResult.Error(
                        message = summary,
                        outcomes = prepared.plan.actions.map(S3SyncAction::toOutcome),
                    )
                }

                prepared.conflictSet != null ->
                    S3SyncResult.Conflict(
                        message = "${prepared.conflictSet.files.size} conflicting file(s) detected",
                        conflicts = prepared.conflictSet,
                    )

                prepared.plan.actions.isEmpty() ->
                    S3SyncResult.Success(
                        message = "S3 already up to date",
                        outcomes = prepared.plan.actions.map(S3SyncAction::toOutcome),
                    )

                else ->
                    S3SyncResult.Success(
                        message = "S3 sync completed",
                        outcomes = prepared.plan.actions.map(S3SyncAction::toOutcome),
                    )
            }
    }

private data class PreparedS3Sync(
    val localFiles: Map<String, LocalS3File>,
    val remoteFiles: Map<String, RemoteS3File>,
    val metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
    val plan: S3SyncPlan,
    val normalActions: List<S3SyncAction>,
    val conflictSet: SyncConflictSet?,
)

private data class S3ActionExecutionResult(
    val actionOutcomes: Map<String, Pair<S3SyncDirection, S3SyncReason>>,
    val failedPaths: List<String>,
    val unresolvedPaths: Set<String>,
    val localChanged: Boolean,
    val localFilesAfterSync: Map<String, LocalS3File>,
    val remoteFilesAfterSync: Map<String, RemoteS3File>,
    val memoRefreshPlan: S3MemoRefreshPlan,
)

private data class IndexedS3ActionExecutionResult(
    val index: Int,
    val action: S3SyncAction,
    val state: S3ActionExecutionState,
)

private fun S3SyncResult.shouldFinalizeAfterSync(): Boolean =
    this is S3SyncResult.Success ||
        (this is S3SyncResult.Error && outcomes.isNotEmpty())

private fun S3SyncResult.stateAfterRefresh(timestamp: Long): S3SyncState =
    when (this) {
        is S3SyncResult.Success -> S3SyncState.Success(timestamp, message)
        is S3SyncResult.Error -> S3SyncState.Error(message, timestamp)
        is S3SyncResult.Conflict -> S3SyncState.ConflictDetected(conflicts)
        S3SyncResult.NotConfigured -> S3SyncState.NotConfigured
    }

private fun S3SyncResult.outcomesForRefreshFailure() =
    when (this) {
        is S3SyncResult.Success -> outcomes
        is S3SyncResult.Error -> outcomes
        is S3SyncResult.Conflict -> emptyList()
        S3SyncResult.NotConfigured -> emptyList()
    }

private fun S3MemoRefreshPlan.merge(other: S3MemoRefreshPlan): S3MemoRefreshPlan =
    when {
        this == S3MemoRefreshPlan.Full || other == S3MemoRefreshPlan.Full -> S3MemoRefreshPlan.Full
        this is S3MemoRefreshPlan.Targets && other is S3MemoRefreshPlan.Targets ->
            S3MemoRefreshPlan.Targets(this.filenames + other.filenames)
        this is S3MemoRefreshPlan.Targets -> this
        other is S3MemoRefreshPlan.Targets -> other
        else -> S3MemoRefreshPlan.None
    }

private const val S3_ACTION_CONCURRENCY = 4
