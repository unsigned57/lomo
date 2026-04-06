package com.lomo.data.repository

import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncReason
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.S3SyncScanPolicy
import com.lomo.domain.model.S3SyncState
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
        private val remoteIndexStore: S3RemoteIndexStore = DisabledS3RemoteIndexStore,
        private val remoteShardStateStore: S3RemoteShardStateStore = DisabledS3RemoteShardStateStore,
    ) {
        suspend fun performSync(
            policy: S3SyncScanPolicy = S3SyncScanPolicy.FAST_ONLY,
        ): S3SyncResult {
            val config = support.resolveConfig() ?: return support.notConfiguredResult()
            val layout = com.lomo.data.sync.SyncDirectoryLayout.resolve(runtime.dataStore)
            val mode = resolveLocalSyncMode(runtime)
            val fileBridgeScope = fileBridge.modeAware(mode)
            return runNonFatalCatching {
                support.withClient(config) { client ->
                    val prepared = prepareSync(client, layout, config, fileBridgeScope, mode, policy)
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
                    val result = buildS3SyncResult(prepared, execution)
                    val now = System.currentTimeMillis()
                    commitIncrementalS3StateIfNeeded(
                        protocolStateStore = protocolStateStore,
                        localChangeJournalStore = localChangeJournalStore,
                        remoteIndexStore = remoteIndexStore,
                        prepared = prepared,
                        execution = execution,
                        result = result,
                        now = now,
                    )
                    finalizeAfterS3Sync(runtime, result, execution)
                }
            }.getOrElse(support::mapError)
        }

        private suspend fun prepareSync(
            client: com.lomo.data.s3.LomoS3Client,
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            config: S3ResolvedConfig,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
            policy: S3SyncScanPolicy,
        ): PreparedS3Sync {
            runtime.stateHolder.state.value = S3SyncState.Initializing
            runtime.stateHolder.state.value = S3SyncState.Listing
            val protocolState = protocolStateStore.read()
            return tryPrepareIncrementalSync(
                client = client,
                layout = layout,
                config = config,
                fileBridgeScope = fileBridgeScope,
                mode = mode,
                policy = policy,
                protocolState = protocolState,
            ) ?: prepareFullSync(
                client = client,
                layout = layout,
                config = config,
                fileBridgeScope = fileBridgeScope,
                mode = mode,
                protocolState = protocolState,
            )
        }

        private suspend fun prepareFullSync(
            client: com.lomo.data.s3.LomoS3Client,
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            config: S3ResolvedConfig,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
            protocolState: S3SyncProtocolState?,
        ): PreparedS3Sync {
            val fullScanEpoch = nextScanEpoch(protocolState)
            val (localFiles, remoteFiles, metadataByPath) =
                coroutineScope {
                    val localFilesDeferred = async { fileBridgeScope.localFiles(layout) }
                    val remoteFilesDeferred =
                        async {
                            fileBridgeScope.remoteFiles(
                                client = client,
                                layout = layout,
                                config = config,
                                scanEpoch = fullScanEpoch,
                                onPageListed = { entries ->
                                    if (remoteIndexStore.remoteIndexEnabled) {
                                        remoteIndexStore.upsert(entries)
                                    }
                                },
                            )
                        }
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
            val conflictSet =
                buildS3ConflictSet(
                    actions = plan.actions.filter { it.direction == S3SyncDirection.CONFLICT },
                    client = client,
                    layout = layout,
                    config = config,
                    remoteFiles = remoteFiles,
                    fileBridgeScope = fileBridgeScope,
                    mode = mode,
                    encodingSupport = encodingSupport,
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
                remoteFileCountHint = remoteFiles.size,
                localModeFingerprint = mode.fingerprint(),
                remoteReconcileState =
                    PreparedRemoteReconcile(
                        observedRemoteEntries = emptyMap(),
                        missingRemotePaths = emptySet(),
                        nextScanCursor = null,
                        scanEpoch = fullScanEpoch,
                        completedScanCycle = true,
                    ),
            )
        }

        private suspend fun tryPrepareIncrementalSync(
            client: com.lomo.data.s3.LomoS3Client,
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            config: S3ResolvedConfig,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
            policy: S3SyncScanPolicy,
            protocolState: S3SyncProtocolState?,
        ): PreparedS3Sync? {
            if (policy == S3SyncScanPolicy.FULL_RECONCILE) {
                return null
            }
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
            if (!protocolStateValue.hasFreshRemoteIndex() || shouldPerformFullLocalAudit(mode, protocolStateValue)) {
                return null
            }
            return prepareIncrementalSyncFromFreshIndex(
                client = client,
                layout = layout,
                config = config,
                fileBridgeScope = fileBridgeScope,
                mode = mode,
                policy = policy,
                protocolState = protocolStateValue,
                journalEntries = effectiveLocalChanges.journalEntries,
            )
        }

        private suspend fun prepareIncrementalSyncFromFreshIndex(
            client: com.lomo.data.s3.LomoS3Client,
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            config: S3ResolvedConfig,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
            policy: S3SyncScanPolicy,
            protocolState: S3SyncProtocolState,
            journalEntries: Map<String, S3LocalChangeJournalEntry>,
        ): PreparedS3Sync {
            val journalEntriesByPath = journalEntries.resolvePaths(layout, mode)
            promoteDirtyRemoteCandidates(
                remoteIndexStore = remoteIndexStore,
                relativePaths = journalEntriesByPath.keys,
            )
            val remoteReconcileState =
                if (shouldRunIncrementalReconcile(policy, protocolState) && remoteIndexStore.remoteIndexEnabled) {
                    prepareRemoteReconcile(
                        client = client,
                        layout = layout,
                        config = config,
                        mode = mode,
                        protocolState = protocolState,
                        encodingSupport = encodingSupport,
                        remoteIndexStore = remoteIndexStore,
                        shardStateStore = remoteShardStateStore,
                    )
                } else {
                    null
                }
            return if (remoteReconcileState == null) {
                prepareLocalOnlyIncrementalSync(
                    client = client,
                    layout = layout,
                    config = config,
                    mode = mode,
                    fileBridgeScope = fileBridgeScope,
                    protocolState = protocolState,
                    journalEntries = journalEntries,
                )
            } else {
                prepareIncrementalSyncWithReconcile(
                    client = client,
                    layout = layout,
                    config = config,
                    fileBridgeScope = fileBridgeScope,
                    mode = mode,
                    protocolState = protocolState,
                    journalEntries = journalEntries,
                    journalEntriesByPath = journalEntriesByPath,
                    remoteReconcileState = remoteReconcileState,
                )
            }
        }

        private suspend fun prepareLocalOnlyIncrementalSync(
            client: com.lomo.data.s3.LomoS3Client,
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            config: S3ResolvedConfig,
            mode: S3LocalSyncMode,
            fileBridgeScope: S3SyncFileBridgeScope,
            protocolState: S3SyncProtocolState,
            journalEntries: Map<String, S3LocalChangeJournalEntry>,
        ): PreparedS3Sync {
            val initialPreparation =
                prepareLocalOnlyIncrementalSync(
                    journalEntries = journalEntries,
                    layout = layout,
                    mode = mode,
                    fileBridgeScope = fileBridgeScope,
                    planner = runtime.planner,
                    metadataDao = runtime.metadataDao,
                    remoteIndexStore = remoteIndexStore,
                )
            val localOnlyIncremental =
                enrichLocalOnlyIncrementalWithRemoteVerification(
                    initial = initialPreparation,
                    client = client,
                )
            val conflictActions =
                localOnlyIncremental.plan.actions.filter { action ->
                    action.direction == S3SyncDirection.CONFLICT
                }
            val conflictPaths = conflictActions.map(S3SyncAction::path).toSet()
            val conflictSet =
                buildS3ConflictSet(
                    actions = conflictActions,
                    client = client,
                    layout = layout,
                    config = config,
                    remoteFiles = localOnlyIncremental.remoteFiles,
                    fileBridgeScope = fileBridgeScope,
                    mode = mode,
                    encodingSupport = encodingSupport,
                )
            if (conflictSet != null) {
                runtime.stateHolder.state.value = S3SyncState.ConflictDetected(conflictSet)
            }
            return buildLocalOnlyPreparedSync(
                layout = layout,
                mode = mode,
                protocolState = protocolState,
                journalEntries = journalEntries,
                localOnlyIncremental = localOnlyIncremental,
                conflictSet = conflictSet,
                conflictPaths = conflictPaths,
            )
        }

        private suspend fun enrichLocalOnlyIncrementalWithRemoteVerification(
            initial: S3IncrementalPreparation,
            client: com.lomo.data.s3.LomoS3Client,
        ): S3IncrementalPreparation {
            val candidatePaths = initial.journalEntriesByPath.keys.toSortedSet()
            if (candidatePaths.isEmpty()) {
                return initial
            }
            val indexedEntriesByPath =
                if (remoteIndexStore.remoteIndexEnabled) {
                    remoteIndexStore.readByRelativePaths(candidatePaths).associateBy(S3RemoteIndexEntry::relativePath)
                } else {
                    emptyMap()
                }
            val verifiedRemoteFiles = initial.remoteFiles.toMutableMap()
            val missingRemoteVerificationByPath = mutableMapOf<String, S3RemoteVerificationLevel>()
            candidatePaths.forEach { path ->
                val remotePath =
                    initial.remoteFiles[path]?.remotePath
                        ?: indexedEntriesByPath[path]?.remotePath
                        ?: initial.metadataByPath[path]?.remotePath
                        ?: return@forEach
                val remoteObject = client.getObjectMetadata(remotePath)
                if (remoteObject == null) {
                    verifiedRemoteFiles.remove(path)
                    missingRemoteVerificationByPath[path] = S3RemoteVerificationLevel.VERIFIED_REMOTE
                } else {
                    verifiedRemoteFiles[path] = remoteObject.toVerifiedRemoteFile(path, encodingSupport)
                }
            }
            val plan =
                runtime.planner.planPaths(
                    paths = candidatePaths,
                    localFiles = initial.localFiles,
                    remoteFiles = verifiedRemoteFiles,
                    metadata = initial.metadataByPath,
                    missingRemoteVerificationByPath = missingRemoteVerificationByPath,
                    defaultMissingRemoteVerification = S3RemoteVerificationLevel.UNKNOWN_REMOTE,
                )
            return initial.copy(
                remoteFiles = verifiedRemoteFiles,
                plan = plan,
            )
        }

        private suspend fun prepareIncrementalSyncWithReconcile(
            client: com.lomo.data.s3.LomoS3Client,
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            config: S3ResolvedConfig,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
            protocolState: S3SyncProtocolState,
            journalEntries: Map<String, S3LocalChangeJournalEntry>,
            journalEntriesByPath: Map<String, S3LocalChangeJournalEntry>,
            remoteReconcileState: PreparedRemoteReconcile,
        ): PreparedS3Sync {
            val candidatePaths =
                (journalEntriesByPath.keys + remoteReconcileState.candidatePaths).toSortedSet()
            val reconcileInputs =
                loadIncrementalReconcileInputs(
                    runtime = runtime,
                    candidatePaths = candidatePaths,
                    journalEntriesByPath = journalEntriesByPath,
                    remoteReconcileState = remoteReconcileState,
                    remoteIndexStore = remoteIndexStore,
                    fileBridgeScope = fileBridgeScope,
                    layout = layout,
                )
            val plan =
                runtime.planner.planPaths(
                    paths = candidatePaths,
                    localFiles = reconcileInputs.localFiles,
                    remoteFiles = reconcileInputs.remoteFiles,
                    metadata = reconcileInputs.plannerMetadataByPath,
                    missingRemoteVerificationByPath =
                        remoteReconcileState.missingRemotePaths.associateWith {
                            S3RemoteVerificationLevel.VERIFIED_REMOTE
                        },
                    defaultMissingRemoteVerification = S3RemoteVerificationLevel.UNKNOWN_REMOTE,
                )
            val (conflictPaths, conflictSet) =
                prepareReconcileConflictState(
                    plan = plan,
                    runtime = runtime,
                    client = client,
                    layout = layout,
                    config = config,
                    remoteFiles = reconcileInputs.remoteFiles,
                    fileBridgeScope = fileBridgeScope,
                    mode = mode,
                    encodingSupport = encodingSupport,
                )
            return PreparedS3Sync(
                layout = layout,
                localFiles = reconcileInputs.localFiles,
                remoteFiles = reconcileInputs.remoteFiles,
                metadataByPath = reconcileInputs.plannerMetadataByPath,
                plan = plan,
                normalActions = plan.actions.filter { it.direction != S3SyncDirection.CONFLICT },
                conflictSet = conflictSet,
                completeSnapshot = false,
                protocolState = protocolState,
                remoteFileCountHint = protocolState.indexedRemoteFileCount,
                journalEntriesById = journalEntries,
                journalPathsById = journalEntriesByPath.entries.associate { (path, entry) -> entry.id to path },
                clearableJournalIds =
                    journalEntriesByPath
                        .filterKeys { path -> path !in conflictPaths }
                        .values
                        .map(S3LocalChangeJournalEntry::id)
                        .toSet(),
                localModeFingerprint = mode.fingerprint(),
                remoteReconcileState = remoteReconcileState,
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
    }
