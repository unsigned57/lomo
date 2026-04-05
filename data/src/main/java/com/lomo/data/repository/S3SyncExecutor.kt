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
        private val protocolStateStore: S3SyncProtocolStateStore = DisabledS3SyncProtocolStateStore,
        private val localChangeJournalStore: S3LocalChangeJournalStore = DisabledS3LocalChangeJournalStore,
        private val remoteManifestStore: S3RemoteManifestStore = DefaultS3RemoteManifestStore(encodingSupport),
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
                        completeSnapshot = prepared.completeSnapshot,
                    )
                    val result = buildSyncResult(prepared, execution)
                    val now = System.currentTimeMillis()
                    commitIncrementalStateIfNeeded(
                        prepared = prepared,
                        execution = execution,
                        result = result,
                        client = client,
                        config = config,
                        now = now,
                    )
                    finalizeAfterSync(result, execution)
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
            val protocolState = protocolStateStore.read()
            val manifestMetadata = loadManifestMetadataOrNull(client, config)
            tryPrepareIncrementalSync(
                client = client,
                layout = layout,
                config = config,
                fileBridgeScope = fileBridgeScope,
                mode = mode,
                protocolState = protocolState,
                manifestMetadata = manifestMetadata,
            )?.let { prepared ->
                return prepared
            }
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
                layout = layout,
                localFiles = localFiles,
                remoteFiles = remoteFiles,
                metadataByPath = metadataByPath,
                plan = plan,
                normalActions = plan.actions.filter { it.direction != S3SyncDirection.CONFLICT },
                conflictSet = conflictSet,
                completeSnapshot = true,
                protocolState = protocolState,
                manifest = null,
                manifestRevision = manifestMetadata?.revision ?: protocolState?.lastManifestRevision,
                remoteFileCountHint = remoteFiles.size,
                localModeFingerprint = mode.fingerprint(),
            )
        }

        private suspend fun tryPrepareIncrementalSync(
            client: com.lomo.data.s3.LomoS3Client,
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            config: S3ResolvedConfig,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
            protocolState: S3SyncProtocolState?,
            manifestMetadata: S3RemoteManifestMetadata?,
        ): PreparedS3Sync? {
            if (!canUseIncrementalSync(mode, protocolState, protocolStateStore, localChangeJournalStore)) {
                return null
            }
            val protocolStateValue = requireNotNull(protocolState)
            val effectiveLocalChanges =
                resolveIncrementalLocalChanges(
                    layout = layout,
                    mode = mode,
                    fileBridgeScope = fileBridgeScope,
                )
            val journalEntries = effectiveLocalChanges.journalEntries
            val localAuditExpired = shouldPerformFullLocalAudit(mode, protocolStateValue)
            val headPrepared =
                tryPrepareSameRevisionFastPath(
                    layout = layout,
                    fileBridgeScope = fileBridgeScope,
                    mode = mode,
                protocolState = protocolStateValue,
                journalEntries = journalEntries,
                manifest = null,
                manifestRevision = manifestMetadata?.revision,
                localAuditExpired = localAuditExpired,
                planner = runtime.planner,
                metadataDao = runtime.metadataDao,
            )
            if (headPrepared != null ||
                (manifestMetadata?.revision != null &&
                    manifestMetadata.revision == protocolStateValue.lastManifestRevision &&
                    localAuditExpired)
            ) {
                return headPrepared
            }
            val manifest =
                loadManifestOrNull(
                    client = client,
                    config = config,
                ) ?: return null
            tryPrepareSameRevisionFastPath(
                layout = layout,
                fileBridgeScope = fileBridgeScope,
                mode = mode,
                protocolState = protocolStateValue,
                journalEntries = journalEntries,
                manifest = manifest,
                manifestRevision = manifest.revision,
                localAuditExpired = localAuditExpired,
                planner = runtime.planner,
                metadataDao = runtime.metadataDao,
            )?.let { return it }
            val incremental =
                prepareIncrementalSync(
                    manifest = manifest,
                    journalEntries = journalEntries,
                    layout = layout,
                    mode = mode,
                    fileBridgeScope = fileBridgeScope,
                    planner = runtime.planner,
                    metadataDao = runtime.metadataDao,
                    protocolState = protocolStateValue,
                )
            val conflictActions = incremental.plan.actions.filter { it.direction == S3SyncDirection.CONFLICT }
            val conflictPaths = conflictActions.map(S3SyncAction::path).toSet()
            val conflictSet =
                buildConflictSet(
                    actions = conflictActions,
                    client = client,
                    layout = layout,
                    config = config,
                    remoteFiles = incremental.remoteFiles,
                    fileBridgeScope = fileBridgeScope,
                    mode = mode,
                )
            if (conflictSet != null) {
                runtime.stateHolder.state.value = S3SyncState.ConflictDetected(conflictSet)
            }
            return buildPreparedIncrementalSync(
                layout = layout,
                protocolState = protocolStateValue,
                mode = mode,
                manifest = manifest,
                journalEntriesById = journalEntries,
                incremental = incremental,
                conflictSet = conflictSet,
                conflictPaths = conflictPaths,
            )
        }

        private suspend fun resolveIncrementalLocalChanges(
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            mode: S3LocalSyncMode,
            fileBridgeScope: S3SyncFileBridgeScope,
        ): S3EffectiveLocalChangeSet {
            val effectiveLocalChanges =
                resolveEffectiveLocalChangeSet(
                    journalEntries = localChangeJournalStore.read(),
                    layout = layout,
                    mode = mode,
                    fileBridgeScope = fileBridgeScope,
                    metadataDao = runtime.metadataDao,
                )
            if (effectiveLocalChanges.stalePersistedIds.isNotEmpty()) {
                localChangeJournalStore.remove(effectiveLocalChanges.stalePersistedIds)
            }
            return effectiveLocalChanges
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

        private suspend fun commitIncrementalStateIfNeeded(
            prepared: PreparedS3Sync,
            execution: S3ActionExecutionResult,
            result: S3SyncResult,
            client: com.lomo.data.s3.LomoS3Client,
            config: S3ResolvedConfig,
            now: Long,
        ) {
            if (!protocolStateStore.incrementalSyncEnabled || !localChangeJournalStore.incrementalSyncEnabled) {
                return
            }
            if (!result.shouldFinalizeAfterSync() || prepared.conflictSet != null) {
                return
            }

            val existingManifest = prepared.manifest
            val shouldWriteManifest =
                prepared.completeSnapshot ||
                    execution.actionOutcomes.isNotEmpty() ||
                    (existingManifest == null && prepared.manifestRevision == null)

            val finalManifest =
                if (shouldWriteManifest) {
                    val manifest =
                        remoteManifestStore.build(
                            remoteFiles = execution.remoteFilesAfterSync,
                            previousRevision =
                                existingManifest?.revision
                                    ?: prepared.manifestRevision
                                    ?: prepared.protocolState?.lastManifestRevision,
                            now = now,
                        )
                    remoteManifestStore.write(client, config, manifest)
                    manifest
                } else {
                    existingManifest
                }

            val previousState = prepared.protocolState
            val indexedCounts = computeIndexedCounts(prepared, execution)
            val finalManifestRevision =
                finalManifest?.revision
                    ?: prepared.manifestRevision
                    ?: previousState?.lastManifestRevision
            val finalRemoteFileCount =
                finalManifest?.entries?.size
                    ?: prepared.remoteFileCountHint
                    ?: previousState?.indexedRemoteFileCount
                    ?: execution.remoteFilesAfterSync.size
            protocolStateStore.write(
                S3SyncProtocolState(
                    protocolVersion = S3_INCREMENTAL_PROTOCOL_VERSION,
                    lastManifestRevision = finalManifestRevision,
                    lastSuccessfulSyncAt = now,
                    indexedLocalFileCount = indexedCounts.first,
                    indexedRemoteFileCount = finalRemoteFileCount,
                    localModeFingerprint = prepared.localModeFingerprint,
                ),
            )

            val retainedJournalPaths = execution.unresolvedPaths + execution.failedPaths
            val clearableJournalIds =
                prepared.clearableJournalIds.filter { id ->
                    val path = prepared.journalPathsById[id] ?: return@filter false
                    path !in retainedJournalPaths
                }
            localChangeJournalStore.remove(clearableJournalIds)
        }

        private suspend fun loadManifestOrNull(
            client: com.lomo.data.s3.LomoS3Client,
            config: S3ResolvedConfig,
        ): S3RemoteManifest? {
            if (!protocolStateStore.incrementalSyncEnabled) {
                return null
            }
            return remoteManifestStore.read(client, config)
        }

        private suspend fun loadManifestMetadataOrNull(
            client: com.lomo.data.s3.LomoS3Client,
            config: S3ResolvedConfig,
        ): S3RemoteManifestMetadata? {
            if (!protocolStateStore.incrementalSyncEnabled) {
                return null
            }
            return remoteManifestStore.readMetadata(client, config)
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
    val layout: com.lomo.data.sync.SyncDirectoryLayout,
    val localFiles: Map<String, LocalS3File>,
    val remoteFiles: Map<String, RemoteS3File>,
    val metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
    val plan: S3SyncPlan,
    val normalActions: List<S3SyncAction>,
    val conflictSet: SyncConflictSet?,
    val completeSnapshot: Boolean,
    val protocolState: S3SyncProtocolState?,
    val manifest: S3RemoteManifest?,
    val manifestRevision: Long?,
    val remoteFileCountHint: Int?,
    val journalEntriesById: Map<String, S3LocalChangeJournalEntry> = emptyMap(),
    val journalPathsById: Map<String, String> = emptyMap(),
    val clearableJournalIds: Set<String> = emptySet(),
    val localModeFingerprint: String? = null,
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

private suspend fun tryPrepareSameRevisionFastPath(
    layout: com.lomo.data.sync.SyncDirectoryLayout,
    fileBridgeScope: S3SyncFileBridgeScope,
    mode: S3LocalSyncMode,
    protocolState: S3SyncProtocolState,
    journalEntries: Map<String, S3LocalChangeJournalEntry>,
    manifest: S3RemoteManifest?,
    manifestRevision: Long?,
    localAuditExpired: Boolean,
    planner: S3SyncPlanner,
    metadataDao: com.lomo.data.local.dao.S3SyncMetadataDao,
): PreparedS3Sync? {
    if (manifestRevision == null || manifestRevision != protocolState.lastManifestRevision || localAuditExpired) {
        return null
    }
    val localOnlyIncremental =
        prepareLocalOnlyIncrementalSync(
            journalEntries = journalEntries,
            layout = layout,
            mode = mode,
            fileBridgeScope = fileBridgeScope,
            planner = planner,
            metadataDao = metadataDao,
        )
    return buildLocalOnlyPreparedSync(
        layout = layout,
        mode = mode,
        protocolState = protocolState,
        manifest = manifest,
        manifestRevision = manifestRevision,
        journalEntries = journalEntries,
        localOnlyIncremental = localOnlyIncremental,
    )
}

private fun buildLocalOnlyPreparedSync(
    layout: com.lomo.data.sync.SyncDirectoryLayout,
    mode: S3LocalSyncMode,
    protocolState: S3SyncProtocolState,
    manifest: S3RemoteManifest?,
    manifestRevision: Long,
    journalEntries: Map<String, S3LocalChangeJournalEntry>,
    localOnlyIncremental: S3LocalOnlyIncrementalPreparation,
): PreparedS3Sync =
    PreparedS3Sync(
        layout = layout,
        localFiles = localOnlyIncremental.localFiles,
        remoteFiles = localOnlyIncremental.remoteFiles,
        metadataByPath = localOnlyIncremental.metadataByPath,
        plan = localOnlyIncremental.plan,
        normalActions =
            localOnlyIncremental.plan.actions.filter { it.direction != S3SyncDirection.CONFLICT },
        conflictSet = null,
        completeSnapshot = false,
        protocolState = protocolState,
        manifest = manifest,
        manifestRevision = manifestRevision,
        remoteFileCountHint = protocolState.indexedRemoteFileCount,
        journalEntriesById = journalEntries,
        journalPathsById =
            localOnlyIncremental.journalEntriesByPath.entries.associate { (path, entry) ->
                entry.id to path
            },
        clearableJournalIds = journalEntries.keys,
        localModeFingerprint = mode.fingerprint(),
    )

private fun buildPreparedIncrementalSync(
    layout: com.lomo.data.sync.SyncDirectoryLayout,
    protocolState: S3SyncProtocolState,
    mode: S3LocalSyncMode,
    manifest: S3RemoteManifest,
    journalEntriesById: Map<String, S3LocalChangeJournalEntry>,
    incremental: S3IncrementalPreparation,
    conflictSet: SyncConflictSet?,
    conflictPaths: Set<String>,
): PreparedS3Sync =
    PreparedS3Sync(
        layout = layout,
        localFiles = incremental.localFiles,
        remoteFiles = incremental.remoteFiles,
        metadataByPath = incremental.metadataByPath,
        plan = incremental.plan,
        normalActions = incremental.plan.actions.filter { it.direction != S3SyncDirection.CONFLICT },
        conflictSet = conflictSet,
        completeSnapshot = false,
        protocolState = protocolState,
        manifest = manifest,
        manifestRevision = manifest.revision,
        remoteFileCountHint = manifest.entries.size,
        journalEntriesById = journalEntriesById,
        journalPathsById =
            incremental.journalEntriesByPath.entries.associate { (path, entry) ->
                entry.id to path
            },
        clearableJournalIds =
            incremental.journalEntriesByPath
                .filterKeys { path -> path !in conflictPaths }
                .values
                .map(S3LocalChangeJournalEntry::id)
                .toSet(),
        localModeFingerprint = mode.fingerprint(),
    )

private fun canUseIncrementalSync(
    mode: S3LocalSyncMode,
    protocolState: S3SyncProtocolState?,
    protocolStateStore: S3SyncProtocolStateStore,
    localChangeJournalStore: S3LocalChangeJournalStore,
): Boolean =
    protocolStateStore.incrementalSyncEnabled &&
        localChangeJournalStore.incrementalSyncEnabled &&
        protocolState != null &&
        protocolState.protocolVersion == S3_INCREMENTAL_PROTOCOL_VERSION &&
        protocolState.localModeFingerprint.compatibleWith(mode)

private fun shouldPerformFullLocalAudit(
    mode: S3LocalSyncMode,
    protocolState: S3SyncProtocolState,
): Boolean =
    mode is S3LocalSyncMode.VaultRoot &&
        (
            protocolState.lastSuccessfulSyncAt == null ||
                System.currentTimeMillis() - protocolState.lastSuccessfulSyncAt > S3_VAULT_ROOT_AUDIT_INTERVAL_MS
        )

private fun S3SyncResult.shouldFinalizeAfterSync(): Boolean =
    this is S3SyncResult.Success ||
        (this is S3SyncResult.Error && outcomes.isNotEmpty())

private fun computeIndexedCounts(
    prepared: PreparedS3Sync,
    execution: S3ActionExecutionResult,
): Pair<Int, Int> {
    if (prepared.completeSnapshot) {
        return execution.localFilesAfterSync.size to execution.remoteFilesAfterSync.size
    }
    var localCount = prepared.protocolState?.indexedLocalFileCount ?: execution.localFilesAfterSync.size
    var remoteCount =
        prepared.remoteFileCountHint
            ?: prepared.protocolState?.indexedRemoteFileCount
            ?: execution.remoteFilesAfterSync.size
    execution.actionOutcomes.forEach { (path, outcome) ->
        val hadMetadata = path in prepared.metadataByPath
        val hadLocalBefore = path in prepared.localFiles
        val hadRemoteBefore = path in prepared.remoteFiles
        when (outcome.first) {
            S3SyncDirection.UPLOAD -> {
                if (!hadMetadata && hadLocalBefore) {
                    localCount += 1
                }
                if (!hadRemoteBefore) {
                    remoteCount += 1
                }
            }

            S3SyncDirection.DOWNLOAD -> {
                if (!hadLocalBefore) {
                    localCount += 1
                }
            }

            S3SyncDirection.DELETE_LOCAL -> {
                if (hadMetadata || hadLocalBefore) {
                    localCount = (localCount - 1).coerceAtLeast(0)
                }
            }

            S3SyncDirection.DELETE_REMOTE -> {
                if (hadMetadata || hadRemoteBefore) {
                    remoteCount = (remoteCount - 1).coerceAtLeast(0)
                }
            }

            S3SyncDirection.NONE,
            S3SyncDirection.CONFLICT,
            -> Unit
        }
    }
    return localCount to remoteCount
}

private fun String?.compatibleWith(mode: S3LocalSyncMode): Boolean =
    this == null || this == mode.fingerprint()

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
