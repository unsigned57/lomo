package com.lomo.data.repository

import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.S3RemoteVerificationLevel
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
        private val pendingConflictStore: PendingSyncConflictStore = DisabledPendingSyncConflictStore,
    ) {
        private val preparedActionVerificationGate =
            S3PreparedActionVerificationGate(
                planner = runtime.planner,
                encodingSupport = encodingSupport,
                remoteIndexStore = remoteIndexStore,
            )
        private val recentActivityTracker = S3RemoteRecentActivityTracker()

        suspend fun performSync(
            policy: S3SyncScanPolicy = S3SyncScanPolicy.FAST_ONLY,
        ): S3SyncResult {
            val config = support.resolveConfig() ?: return support.notConfiguredResult()
            val layout = com.lomo.data.sync.SyncDirectoryLayout.resolve(runtime.dataStore)
            val mode = resolveLocalSyncMode(runtime)
            val fileBridgeScope = fileBridge.modeAware(mode)
            return runNonFatalCatching {
                support.withClient(config) { client ->
                    val prepared = prepareFastSync(client, layout, config, fileBridgeScope, mode, policy)
                    val verified =
                        verifyDestructiveCandidates(
                            prepared = prepared,
                            client = client,
                            layout = layout,
                            config = config,
                            fileBridgeScope = fileBridgeScope,
                            mode = mode,
                            verificationGate = preparedActionVerificationGate,
                            encodingSupport = encodingSupport,
                        )
                    val execution =
                        applyPreparedActions(
                            verified = verified,
                            client = client,
                            layout = layout,
                            config = config,
                            fileBridgeScope = fileBridgeScope,
                            mode = mode,
                            actionApplier = actionApplier,
                        )
                    val result = buildS3SyncResult(verified.prepared, execution)
                    runtime.transactionRunner.runInTransaction {
                        persistAppliedS3Actions(
                            fileBridge = fileBridge,
                            prepared = verified.prepared,
                            execution = execution,
                        )
                        reconcileRemoteIndexAfterSyncIfNeeded(
                            protocolStateStore = protocolStateStore,
                            localChangeJournalStore = localChangeJournalStore,
                            remoteIndexStore = remoteIndexStore,
                            recentActivityTracker = recentActivityTracker,
                            prepared = verified.prepared,
                            execution = execution,
                            result = result,
                            now = System.currentTimeMillis(),
                        )
                    }
                    finalizeAfterS3Sync(runtime, result, execution)
                }
            }.getOrElse(support::mapError)
        }

        private suspend fun prepareFastSync(
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
            val fullSnapshot =
                loadFullSyncSnapshot(
                    runtime = runtime,
                    remoteIndexStore = remoteIndexStore,
                    fileBridgeScope = fileBridgeScope,
                    client = client,
                    layout = layout,
                    config = config,
                    fullScanEpoch = fullScanEpoch,
                )
            val localFiles = fullSnapshot.localFiles
            val remoteFiles = fullSnapshot.remoteFiles
            val metadataByPath = fullSnapshot.metadataByPath
            val initialClassification =
                classifyInitialOverlaps(
                    localFiles = localFiles,
                    remoteFiles = remoteFiles,
                    metadataByPath = metadataByPath,
                    client = client,
                    config = config,
                    encodingSupport = encodingSupport,
                    fileBridgeScope = fileBridgeScope,
                    layout = layout,
                    mode = mode,
                    timestampToleranceMs = runtime.planner.timestampToleranceMs,
                )
            val initialPlan =
                runtime.planner.plan(
                    localFiles = localFiles,
                    remoteFiles = remoteFiles,
                    metadata = metadataByPath,
                    preResolvedActionsByPath = initialClassification.resolvedActionsByPath,
                    suppressedPaths = initialClassification.equivalentMetadataByPath.keys,
                )
            val plan =
                refineTrackedMemoPlanWithContent(
                    plan = initialPlan,
                    localFiles = localFiles,
                    remoteFiles = remoteFiles,
                    metadataByPath = metadataByPath,
                    client = client,
                    config = config,
                    encodingSupport = encodingSupport,
                    fileBridgeScope = fileBridgeScope,
                    layout = layout,
                    mode = mode,
                )
            val conflictActions =
                plan.actions.filter { it.direction == S3SyncDirection.CONFLICT }
            val conflictSet =
                buildS3ConflictSet(
                    actions = conflictActions,
                    client = client,
                    layout = layout,
                    config = config,
                    remoteFiles = remoteFiles,
                    fileBridgeScope = fileBridgeScope,
                    mode = mode,
                    encodingSupport = encodingSupport,
                    sessionKind = determineS3ConflictSessionKind(conflictActions, metadataByPath),
                    lightweightPreview = initialClassification.lightweightConflictPreview,
                )
            if (conflictSet != null) {
                pendingConflictStore.write(conflictSet)
                runtime.stateHolder.state.value = conflictSet.toS3ConflictState()
            }
            return PreparedS3Sync(
                layout = layout,
                localFiles = localFiles,
                remoteFiles = remoteFiles,
                metadataByPath = metadataByPath,
                seededMetadataByPath = initialClassification.equivalentMetadataByPath,
                preResolvedActionsByPath = initialClassification.resolvedActionsByPath,
                plan = plan,
                normalActions = plan.actions.filter { it.direction != S3SyncDirection.CONFLICT },
                conflictSet = conflictSet,
                completeSnapshot = true,
                protocolState = protocolState,
                remoteFileCountHint = remoteFiles.size,
                localModeFingerprint = mode.fingerprint(),
                remoteReconcileState = completedFullSyncReconcileState(fullScanEpoch),
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
            if (
                !protocolStateValue.hasFreshRemoteIndex(config) ||
                shouldPerformFullLocalAudit(mode, protocolStateValue)
            ) {
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
            recentActivityTracker.recordForegroundCandidates(
                remoteIndexStore = remoteIndexStore,
                relativePaths = journalEntriesByPath.keys,
                scanEpoch = protocolState.scanEpoch,
            )
            val remoteReconcileState =
                if (
                    shouldRunIncrementalReconcile(policy, config, protocolState) &&
                    remoteIndexStore.remoteIndexEnabled
                ) {
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
                    config = config,
                    fileBridgeScope = fileBridgeScope,
                    layout = layout,
                    mode = mode,
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
                    sessionKind =
                        determineS3ConflictSessionKind(
                            conflictActions = conflictActions,
                            metadataByPath = localOnlyIncremental.metadataByPath,
                        ),
                )
            if (conflictSet != null) {
                pendingConflictStore.write(conflictSet)
                runtime.stateHolder.state.value = conflictSet.toS3ConflictState()
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
            config: S3ResolvedConfig,
            fileBridgeScope: S3SyncFileBridgeScope,
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            mode: S3LocalSyncMode,
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
            val initialPlan =
                runtime.planner.planPaths(
                    paths = candidatePaths,
                    localFiles = initial.localFiles,
                    remoteFiles = verifiedRemoteFiles,
                    metadata = initial.metadataByPath,
                    missingRemoteVerificationByPath = missingRemoteVerificationByPath,
                    defaultMissingRemoteVerification = S3RemoteVerificationLevel.UNKNOWN_REMOTE,
                )
            val plan =
                refineTrackedMemoPlanWithContent(
                    plan = initialPlan,
                    localFiles = initial.localFiles,
                    remoteFiles = verifiedRemoteFiles,
                    metadataByPath = initial.metadataByPath,
                    client = client,
                    config = config,
                    encodingSupport = encodingSupport,
                    fileBridgeScope = fileBridgeScope,
                    layout = layout,
                    mode = mode,
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
            val initialPlan =
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
            val plan =
                refineTrackedMemoPlanWithContent(
                    plan = initialPlan,
                    localFiles = reconcileInputs.localFiles,
                    remoteFiles = reconcileInputs.remoteFiles,
                    metadataByPath = reconcileInputs.plannerMetadataByPath,
                    client = client,
                    config = config,
                    encodingSupport = encodingSupport,
                    fileBridgeScope = fileBridgeScope,
                    layout = layout,
                    mode = mode,
                )
            val (conflictPaths, conflictSet) =
                prepareReconcileConflictState(
                    plan = plan,
                    runtime = runtime,
                    pendingConflictStore = pendingConflictStore,
                    client = client,
                    layout = layout,
                    config = config,
                    remoteFiles = reconcileInputs.remoteFiles,
                    fileBridgeScope = fileBridgeScope,
                    mode = mode,
                    encodingSupport = encodingSupport,
                    metadataByPath = reconcileInputs.plannerMetadataByPath,
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
    }

private data class FullSyncSnapshot(
    val localFiles: Map<String, LocalS3File>,
    val remoteFiles: Map<String, RemoteS3File>,
    val metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
)

private suspend fun loadFullSyncSnapshot(
    runtime: S3SyncRepositoryContext,
    remoteIndexStore: S3RemoteIndexStore,
    fileBridgeScope: S3SyncFileBridgeScope,
    client: com.lomo.data.s3.LomoS3Client,
    layout: com.lomo.data.sync.SyncDirectoryLayout,
    config: S3ResolvedConfig,
    fullScanEpoch: Long,
): FullSyncSnapshot =
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
                runtime.metadataDao.readAllPlannerMetadataByPath()
            }
        FullSyncSnapshot(
            localFiles = localFilesDeferred.await(),
            remoteFiles = remoteFilesDeferred.await(),
            metadataByPath = metadataByPathDeferred.await(),
        )
    }

private suspend fun applyPreparedActions(
    verified: VerifiedPreparedS3Sync,
    client: com.lomo.data.s3.LomoS3Client,
    layout: com.lomo.data.sync.SyncDirectoryLayout,
    config: S3ResolvedConfig,
    fileBridgeScope: S3SyncFileBridgeScope,
    mode: S3LocalSyncMode,
    actionApplier: S3SyncActionApplier,
): S3ActionExecutionResult =
    coroutineScope {
        val concurrencyLimiter = Semaphore(S3_ACTION_CONCURRENCY)
        val indexedResults =
            verified.prepared.normalActions.mapIndexed { index, action ->
                async {
                    concurrencyLimiter.withWeightedPermit(
                        permitsForS3Action(
                            action = action,
                            localFiles = verified.prepared.localFiles,
                            remoteFiles = verified.prepared.remoteFiles,
                            metadataByPath = verified.prepared.metadataByPath,
                        ),
                    ) {
                        IndexedS3ActionExecutionResult(
                            index = index,
                            action = action,
                            state =
                                actionApplier.applyAction(
                                    action = action,
                                    client = client,
                                    layout = layout,
                                    config = config,
                                    localFiles = verified.prepared.localFiles,
                                    remoteFiles = verified.prepared.remoteFiles,
                                    metadataByPath = verified.prepared.metadataByPath,
                                    verifiedMissingRemotePaths = verified.verifiedMissingRemotePaths,
                                    fileBridgeScope = fileBridgeScope,
                                    mode = mode,
                                ),
                        )
                    }
                }
            }.awaitAll().sortedBy(IndexedS3ActionExecutionResult::index)

        val actionOutcomes = mutableMapOf<String, Pair<S3SyncDirection, S3SyncReason>>()
        val syncedContentFingerprints = mutableMapOf<String, String>()
        val failedPaths = mutableListOf<String>()
        val unresolvedPaths = mutableSetOf<String>()
        val localFilesAfterSync = verified.prepared.localFiles.toMutableMap()
        val remoteFilesAfterSync = verified.prepared.remoteFiles.toMutableMap()
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
                    result.syncedContentFingerprint?.let { fingerprint ->
                        syncedContentFingerprints[execution.action.path] = fingerprint
                    }
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
            syncedContentFingerprints = syncedContentFingerprints,
            failedPaths = failedPaths,
            unresolvedPaths = unresolvedPaths,
            localChanged = localChanged,
            localFilesAfterSync = localFilesAfterSync,
            remoteFilesAfterSync = remoteFilesAfterSync,
            memoRefreshPlan = memoRefreshPlan,
        )
    }

private fun completedFullSyncReconcileState(scanEpoch: Long): PreparedRemoteReconcile =
    PreparedRemoteReconcile(
        observedRemoteEntries = emptyMap(),
        missingRemotePaths = emptySet(),
        nextScanCursor = null,
        scanEpoch = scanEpoch,
        completedScanCycle = true,
    )

internal data class VerifiedPreparedS3Sync(
    val prepared: PreparedS3Sync,
    val verifiedMissingRemotePaths: Set<String> = emptySet(),
)

private suspend fun verifyDestructiveCandidates(
    prepared: PreparedS3Sync,
    client: com.lomo.data.s3.LomoS3Client,
    layout: com.lomo.data.sync.SyncDirectoryLayout,
    config: S3ResolvedConfig,
    fileBridgeScope: S3SyncFileBridgeScope,
    mode: S3LocalSyncMode,
    verificationGate: S3PreparedActionVerificationGate,
    encodingSupport: S3SyncEncodingSupport,
): VerifiedPreparedS3Sync {
    val verified =
        verificationGate.verify(
            prepared = prepared,
            client = client,
            config = config,
            layout = layout,
            fileBridgeScope = fileBridgeScope,
            mode = mode,
        )
    val conflictSet = verified.prepared.conflictSet
    val conflictActions =
        verified.prepared.plan.actions.filter { action ->
            action.direction == S3SyncDirection.CONFLICT
        }
    if (conflictSet != null || conflictActions.isEmpty()) {
        return verified
    }
    val rebuiltConflictSet =
        buildS3ConflictSet(
            actions = conflictActions,
            client = client,
            layout = layout,
            config = config,
            remoteFiles = verified.prepared.remoteFiles,
            fileBridgeScope = fileBridgeScope,
            mode = mode,
            encodingSupport = encodingSupport,
            sessionKind =
                determineS3ConflictSessionKind(
                    conflictActions = conflictActions,
                    metadataByPath = verified.prepared.metadataByPath,
                ),
        )
    return verified.copy(
        prepared =
            verified.prepared.copy(
                conflictSet = rebuiltConflictSet,
                normalActions =
                    verified.prepared.plan.actions.filter { action ->
                        action.direction != S3SyncDirection.CONFLICT
                    },
            ),
    )
}

private suspend fun persistAppliedS3Actions(
    fileBridge: S3SyncFileBridge,
    prepared: PreparedS3Sync,
    execution: S3ActionExecutionResult,
) {
    fileBridge.persistMetadata(
        localFiles = execution.localFilesAfterSync,
        remoteFiles = execution.remoteFilesAfterSync,
        metadataByPath = prepared.metadataByPath,
        seededMetadataByPath = prepared.seededMetadataByPath,
        actionOutcomes = execution.actionOutcomes,
        syncedContentFingerprints = execution.syncedContentFingerprints,
        unresolvedPaths = execution.unresolvedPaths,
        completeSnapshot = prepared.completeSnapshot,
    )
}

private suspend fun reconcileRemoteIndexAfterSyncIfNeeded(
    protocolStateStore: S3SyncProtocolStateStore,
    localChangeJournalStore: S3LocalChangeJournalStore,
    remoteIndexStore: S3RemoteIndexStore,
    recentActivityTracker: S3RemoteRecentActivityTracker,
    prepared: PreparedS3Sync,
    execution: S3ActionExecutionResult,
    result: S3SyncResult,
    now: Long,
) {
    commitIncrementalS3StateIfNeeded(
        protocolStateStore = protocolStateStore,
        localChangeJournalStore = localChangeJournalStore,
        remoteIndexStore = remoteIndexStore,
        recentActivityTracker = recentActivityTracker,
        prepared = prepared,
        execution = execution,
        result = result,
        now = now,
    )
}

private suspend fun <T> Semaphore.withWeightedPermit(
    permits: Int,
    block: suspend () -> T,
): T {
    val acquiredPermits = permits.coerceIn(1, S3_ACTION_CONCURRENCY)
    repeat(acquiredPermits) { acquire() }
    return try {
        block()
    } finally {
        repeat(acquiredPermits) { release() }
    }
}
