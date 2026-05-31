package com.lomo.data.repository

import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncReason
import com.lomo.domain.model.S3SyncResult
import com.lomo.data.repository.S3SyncWorkIntent
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictSet
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
    internal constructor(
        private val runtime: S3SyncRepositoryContext,
        private val support: S3SyncRepositorySupport,
        private val encodingSupport: S3SyncEncodingSupport,
        private val fileBridge: S3SyncFileBridge,
        private val actionApplier: S3SyncActionApplier,
        private val lifecycleRunner: RemoteSyncLifecycleRunner,
        private val protocolStateStore: S3SyncProtocolStateStore,
        private val localChangeJournalStore: S3LocalChangeJournalStore,
        private val remoteIndexStore: S3RemoteIndexStore,
        private val remoteShardStateStore: S3RemoteShardStateStore,
        private val pendingConflictStore: PendingSyncConflictStore,
        private val pendingReviewStore: PendingSyncReviewStore,
    ) {
        private val preparedActionVerificationGate =
            S3PreparedActionVerificationGate(
                planner = runtime.planner,
                encodingSupport = encodingSupport,
                performanceTuner = runtime.performanceTuner,
                remoteIndexStore = remoteIndexStore,
            )
        private val recentActivityTracker = S3RemoteRecentActivityTracker()
        private val snapshotLoader =
            S3SyncSnapshotLoader(
                runtime = runtime,
                encodingSupport = encodingSupport,
                protocolStateStore = protocolStateStore,
                localChangeJournalStore = localChangeJournalStore,
                remoteIndexStore = remoteIndexStore,
                remoteShardStateStore = remoteShardStateStore,
                recentActivityTracker = recentActivityTracker,
            )
        private val pendingConflictRestorer =
            S3PendingConflictSessionRestorer(
                runtime = runtime,
                support = support,
                encodingSupport = encodingSupport,
                fileBridge = fileBridge,
                lifecycleRunner = lifecycleRunner,
            )

        suspend fun performSync(
            policy: S3SyncWorkIntent = S3SyncWorkIntent.FAST_ONLY,
        ): S3SyncResult {
            val config = support.resolveConfig() ?: return support.notConfiguredResult()
            val layout = com.lomo.data.sync.SyncDirectoryLayout.resolve(runtime.dataStore)
            val mode = resolveLocalSyncMode(runtime)
            val fileBridgeScope = fileBridge.modeAware(mode)
            return support.runS3Io {
                lifecycleRunner.run(
                    S3SyncLifecycleStages(
                        config = config,
                        layout = layout,
                        mode = mode,
                        fileBridgeScope = fileBridgeScope,
                        policy = policy,
                    ),
                )
            }
        }

        suspend fun restorePendingConflict(
            descriptor: PendingSyncConflictDescriptor,
        ): PendingSyncRestoreResult<SyncConflictSet> = pendingConflictRestorer.restore(descriptor)

        private inner class S3SyncLifecycleStages(
            private val config: S3ResolvedConfig,
            private val layout: com.lomo.data.sync.SyncDirectoryLayout,
            private val mode: S3LocalSyncMode,
            private val fileBridgeScope: S3SyncFileBridgeScope,
            private val policy: S3SyncWorkIntent,
        ) : RemoteSyncLifecycleStages<
                S3SyncLifecycleSnapshot,
                PreparedS3Sync,
                VerifiedPreparedS3Sync,
                S3ConflictMaterialization,
                S3ActionExecutionResult,
                S3CommittedSync,
                S3SyncResult,
                S3SyncResult,
            > {
            override val context: RemoteSyncLifecycleContext =
                RemoteSyncLifecycleContext(
                    backend = SyncBackendType.S3,
                    budget = RemoteSyncBudgetPolicy.Limited(DEFAULT_REMOTE_SYNC_NETWORK_OPERATION_BUDGET),
                )

            private var client: com.lomo.data.s3.LomoS3Client? = null

            override suspend fun loadSnapshot(session: RemoteSyncLifecycleSession): S3SyncLifecycleSnapshot {
                client = session.meter(support.createClient(config))
                runtime.stateHolder.state.value = S3SyncState.Initializing
                runtime.stateHolder.state.value = S3SyncState.Listing
                val protocolState = protocolStateStore.read()
                val loaded =
                    snapshotLoader.loadS3PlanningSnapshot(
                        client = requireClient(),
                        layout = layout,
                        config = config,
                        fileBridgeScope = fileBridgeScope,
                        mode = mode,
                        policy = policy,
                        protocolState = protocolState,
                    )
                return S3SyncLifecycleSnapshot(
                    client = requireClient(),
                    loaded = loaded,
                )
            }

            override suspend fun plan(
                snapshot: S3SyncLifecycleSnapshot,
                session: RemoteSyncLifecycleSession,
            ): PreparedS3Sync =
                prepareFastSyncPlan(
                    client = snapshot.client,
                    layout = layout,
                    config = config,
                    fileBridgeScope = fileBridgeScope,
                    mode = mode,
                    loaded = snapshot.loaded,
                )

            override suspend fun verify(
                plan: PreparedS3Sync,
                session: RemoteSyncLifecycleSession,
            ): VerifiedPreparedS3Sync =
                verifyDestructiveCandidates(
                    prepared = plan,
                    client = requireClient(),
                    layout = layout,
                    config = config,
                    fileBridgeScope = fileBridgeScope,
                    mode = mode,
                    verificationGate = preparedActionVerificationGate,
                )

            override suspend fun materializeConflicts(
                verified: VerifiedPreparedS3Sync,
                session: RemoteSyncLifecycleSession,
            ): S3ConflictMaterialization {
                val materialized =
                    materializeS3Conflicts(
                        verified = verified,
                        client = requireClient(),
                        layout = layout,
                        config = config,
                        fileBridgeScope = fileBridgeScope,
                        mode = mode,
                    )
                materialized.reviewSession?.let { review ->
                    pendingReviewStore.write(review)
                    runtime.stateHolder.state.value = S3SyncState.PreviewingInitialSync(review)
                }
                materialized.conflictSet?.let { conflictSet ->
                    pendingConflictStore.write(conflictSet)
                    runtime.stateHolder.state.value = S3SyncState.ConflictDetected(conflictSet)
                }
                return materialized
            }

            override suspend fun apply(
                verified: VerifiedPreparedS3Sync,
                conflicts: S3ConflictMaterialization,
                session: RemoteSyncLifecycleSession,
            ): S3ActionExecutionResult {
                val profile = runtime.performanceTuner.currentProfile()
                return applyPreparedActions(
                    verified = conflicts.verified,
                    client = requireClient(),
                    layout = layout,
                    config = config,
                    fileBridgeScope = fileBridgeScope,
                    mode = mode,
                    actionApplier = actionApplier,
                    actionConcurrency = profile.s3ActionConcurrency,
                    largeTransferConcurrency = profile.s3LargeTransferConcurrency,
                )
            }

            override suspend fun commitMetadata(
                verified: VerifiedPreparedS3Sync,
                conflicts: S3ConflictMaterialization,
                applied: S3ActionExecutionResult,
                session: RemoteSyncLifecycleSession,
            ): S3CommittedSync {
                val executionConflictSet =
                    materializeExecutionConflicts(
                        verified = conflicts.verified,
                        execution = applied,
                        client = requireClient(),
                        layout = layout,
                        config = config,
                        fileBridgeScope = fileBridgeScope,
                        mode = mode,
                    )
                executionConflictSet?.let { conflictSet ->
                    pendingConflictStore.write(conflictSet)
                    runtime.stateHolder.state.value = S3SyncState.ConflictDetected(conflictSet)
                }
                val result =
                    buildS3SyncResult(
                        prepared = conflicts.verified.prepared,
                        execution = applied,
                        conflictSet = conflicts.conflictSet ?: executionConflictSet,
                        reviewSession = conflicts.reviewSession,
                    )
                runtime.transactionRunner.runInTransaction {
                    persistAppliedS3Actions(
                        fileBridge = fileBridge,
                        prepared = conflicts.verified.prepared,
                        execution = applied,
                    )
                    reconcileRemoteIndexAfterSyncIfNeeded(
                        protocolStateStore = protocolStateStore,
                        localChangeJournalStore = localChangeJournalStore,
                        remoteIndexStore = remoteIndexStore,
                        recentActivityTracker = recentActivityTracker,
                        prepared = conflicts.verified.prepared,
                        execution = applied,
                        result = result,
                        hasMaterializedConflict =
                            conflicts.conflictSet != null ||
                                executionConflictSet != null ||
                                conflicts.reviewSession != null,
                        now = System.currentTimeMillis(),
                    )
                }
                return S3CommittedSync(result = result, execution = applied)
            }

            override suspend fun finalize(
                verified: VerifiedPreparedS3Sync,
                conflicts: S3ConflictMaterialization,
                applied: S3ActionExecutionResult,
                metadata: S3CommittedSync,
                session: RemoteSyncLifecycleSession,
            ): S3SyncResult =
                finalizeAfterS3Sync(
                    runtime = runtime,
                    result = metadata.result,
                    execution = metadata.execution,
                )

            override fun summarizeSnapshot(snapshot: S3SyncLifecycleSnapshot): RemoteSyncSnapshotTelemetry =
                RemoteSyncSnapshotTelemetry(
                    localFileCount = snapshot.localFiles.size,
                    remoteFileCount = snapshot.remoteFiles.size,
                    metadataEntryCount = snapshot.metadataByPath.size,
                )

            override fun summarizePlan(plan: PreparedS3Sync): RemoteSyncActionTelemetry =
                plan.plan.actions.toRemoteSyncActionTelemetry()

            override fun summarizeVerification(verified: VerifiedPreparedS3Sync): RemoteSyncActionTelemetry =
                verified.prepared.plan.actions.toRemoteSyncActionTelemetry()

            override fun summarizeRefresh(finalized: S3SyncResult): RemoteSyncRefreshTelemetry =
                RemoteSyncRefreshTelemetry(durationMillis = 0)

            override fun mapResult(finalized: S3SyncResult): S3SyncResult = finalized

            override fun mapError(error: Throwable): S3SyncResult = support.mapError(error)

            override suspend fun release() {
                client?.close()
                client = null
            }

            private fun requireClient(): com.lomo.data.s3.LomoS3Client =
                checkNotNull(client) { "S3 lifecycle client has not been initialized" }
        }

        private suspend fun prepareFastSyncPlan(
            client: com.lomo.data.s3.LomoS3Client,
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            config: S3ResolvedConfig,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
            loaded: LoadedS3PlanningSnapshot,
        ): PreparedS3Sync =
            when (loaded) {
                is LoadedS3PlanningSnapshot.Full ->
                    prepareFullSyncPlan(
                        client = client,
                        layout = layout,
                        config = config,
                        fileBridgeScope = fileBridgeScope,
                        mode = mode,
                        snapshot = loaded,
                    )

                is LoadedS3PlanningSnapshot.LocalOnlyIncremental ->
                    prepareLocalOnlyIncrementalSyncPlan(
                        client = client,
                        layout = layout,
                        config = config,
                        fileBridgeScope = fileBridgeScope,
                        mode = mode,
                        snapshot = loaded,
                    )

                is LoadedS3PlanningSnapshot.ReconcileIncremental ->
                    prepareIncrementalSyncWithReconcilePlan(
                        client = client,
                        layout = layout,
                        config = config,
                        fileBridgeScope = fileBridgeScope,
                        mode = mode,
                        snapshot = loaded,
                    )
            }

        private suspend fun prepareFullSyncPlan(
            client: com.lomo.data.s3.LomoS3Client,
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            config: S3ResolvedConfig,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
            snapshot: LoadedS3PlanningSnapshot.Full,
        ): PreparedS3Sync {
            val localFiles = snapshot.localFiles
            val remoteFiles = snapshot.remoteFiles
            val metadataByPath = snapshot.metadataByPath
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
                    localFiles = localFiles.toS3RemoteSyncLocalSnapshots(),
                    remoteFiles = remoteFiles.toS3RemoteSyncRemoteSnapshots(),
                    metadata = metadataByPath.toS3RemoteSyncMetadataSnapshots(),
                    preResolvedActionsByPath = initialClassification.resolvedActionsByPath.toS3RemoteSyncActions(),
                    suppressedPaths = initialClassification.equivalentMetadataByPath.keys,
                ).toS3Plan()
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
            return PreparedS3Sync(
                layout = layout,
                localFiles = localFiles,
                remoteFiles = remoteFiles,
                metadataByPath = metadataByPath,
                seededMetadataByPath = initialClassification.equivalentMetadataByPath,
                preResolvedActionsByPath = initialClassification.resolvedActionsByPath,
                plan = plan,
                normalActions = plan.actions.filter { it.direction != S3SyncDirection.CONFLICT },
                completeSnapshot = true,
                protocolState = snapshot.protocolState,
                remoteFileCountHint = remoteFiles.size,
                localModeFingerprint = mode.fingerprint(),
                remoteReconcileState = completedFullSyncReconcileState(snapshot.fullScanEpoch),
                conflictPreview = S3ConflictPreview(lightweight = initialClassification.lightweightConflictPreview),
            )
        }

        private suspend fun prepareLocalOnlyIncrementalSyncPlan(
            client: com.lomo.data.s3.LomoS3Client,
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            config: S3ResolvedConfig,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
            snapshot: LoadedS3PlanningSnapshot.LocalOnlyIncremental,
        ): PreparedS3Sync {
            val localOnlyIncremental =
                enrichLocalOnlyIncrementalWithRemoteVerification(
                    initial = snapshot.initialPreparation,
                    protocolState = snapshot.protocolState,
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
            return buildLocalOnlyPreparedSync(
                layout = layout,
                mode = mode,
                protocolState = snapshot.protocolState,
                journalEntries = snapshot.journalEntries,
                localOnlyIncremental = localOnlyIncremental,
                conflictPaths = conflictPaths,
            )
        }

        private suspend fun enrichLocalOnlyIncrementalWithRemoteVerification(
            initial: S3IncrementalPreparation,
            protocolState: S3SyncProtocolState,
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
            val actionsByPath = initial.plan.actions.associateBy(S3SyncAction::path)
            val pathsToVerify =
                candidatePaths.filterTo(linkedSetOf()) { path ->
                    S3RemoteVerificationPolicy.shouldHeadLocalOnlyIncrementalPath(
                        action = actionsByPath[path],
                        path = path,
                        initial = initial,
                        protocolState = protocolState,
                        remoteIndexEntry = indexedEntriesByPath[path],
                        config = config,
                    )
                }

            data class VerificationResult(
                val path: String,
                val remoteObject: com.lomo.data.s3.S3RemoteObject?,
            )

            val verificationLimiter =
                Semaphore(
                    runtime.performanceTuner.currentProfile().s3VerificationConcurrency.coercePositiveConcurrency(),
                )
            val results = coroutineScope {
                pathsToVerify.map { path ->
                    async {
                        val remotePath =
                            initial.remoteFiles[path]?.remotePath
                                ?: indexedEntriesByPath[path]?.remotePath
                                ?: initial.metadataByPath[path]?.remotePath
                                ?: return@async null
                        verificationLimiter.withPermit {
                            val remoteObject = client.getObjectMetadata(remotePath)
                            VerificationResult(path = path, remoteObject = remoteObject)
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            for (result in results) {
                if (result.remoteObject == null) {
                    verifiedRemoteFiles.remove(result.path)
                    missingRemoteVerificationByPath[result.path] = S3RemoteVerificationLevel.VERIFIED_REMOTE
                } else {
                    verifiedRemoteFiles[result.path] =
                        result.remoteObject.toVerifiedRemoteFile(result.path, encodingSupport)
                }
            }
            val initialPlan =
                runtime.planner.planPaths(
                    paths = candidatePaths,
                    localFiles = initial.localFiles.toS3RemoteSyncLocalSnapshots(),
                    remoteFiles = verifiedRemoteFiles.toS3RemoteSyncRemoteSnapshots(),
                    metadata = initial.metadataByPath.toS3RemoteSyncMetadataSnapshots(),
                    missingRemoteVerificationByPath =
                        missingRemoteVerificationByPath.toS3RemoteSyncRemoteAbsenceVerifications(),
                    defaultMissingRemoteVerification =
                        S3RemoteVerificationLevel.UNKNOWN_REMOTE.toRemoteSyncRemoteAbsenceVerification(),
                ).toS3Plan()
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

        private suspend fun prepareIncrementalSyncWithReconcilePlan(
            client: com.lomo.data.s3.LomoS3Client,
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            config: S3ResolvedConfig,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
            snapshot: LoadedS3PlanningSnapshot.ReconcileIncremental,
        ): PreparedS3Sync {
            val initialPlan =
                runtime.planner.planPaths(
                    paths = snapshot.candidatePaths,
                    localFiles = snapshot.reconcileInputs.localFiles.toS3RemoteSyncLocalSnapshots(),
                    remoteFiles = snapshot.reconcileInputs.remoteFiles.toS3RemoteSyncRemoteSnapshots(),
                    metadata = snapshot.reconcileInputs.plannerMetadataByPath.toS3RemoteSyncMetadataSnapshots(),
                    missingRemoteVerificationByPath =
                        snapshot.remoteReconcileState.missingRemotePaths.associateWith {
                            S3RemoteVerificationLevel.VERIFIED_REMOTE
                        }.toS3RemoteSyncRemoteAbsenceVerifications(),
                    defaultMissingRemoteVerification =
                        S3RemoteVerificationLevel.UNKNOWN_REMOTE.toRemoteSyncRemoteAbsenceVerification(),
                ).toS3Plan()
            val plan =
                refineTrackedMemoPlanWithContent(
                    plan = initialPlan,
                    localFiles = snapshot.reconcileInputs.localFiles,
                    remoteFiles = snapshot.reconcileInputs.remoteFiles,
                    metadataByPath = snapshot.reconcileInputs.plannerMetadataByPath,
                    client = client,
                    config = config,
                    encodingSupport = encodingSupport,
                    fileBridgeScope = fileBridgeScope,
                    layout = layout,
                    mode = mode,
                )
            val conflictPaths =
                plan.actions
                    .filter { it.direction == S3SyncDirection.CONFLICT }
                    .map(S3SyncAction::path)
                    .toSet()
            return PreparedS3Sync(
                layout = layout,
                localFiles = snapshot.reconcileInputs.localFiles,
                remoteFiles = snapshot.reconcileInputs.remoteFiles,
                metadataByPath = snapshot.reconcileInputs.plannerMetadataByPath,
                plan = plan,
                normalActions = plan.actions.filter { it.direction != S3SyncDirection.CONFLICT },
                completeSnapshot = false,
                protocolState = snapshot.protocolState,
                remoteFileCountHint = snapshot.protocolState.indexedRemoteFileCount,
                journalEntriesById = snapshot.journalEntries,
                journalPathsById =
                    snapshot.journalEntriesByPath.entries.associate { (path, entry) ->
                        entry.id to path
                    },
                clearableJournalIds =
                    snapshot.journalEntriesByPath
                        .filterKeys { path -> path !in conflictPaths }
                        .values
                        .map(S3LocalChangeJournalEntry::id)
                        .toSet(),
                localModeFingerprint = mode.fingerprint(),
                remoteReconcileState = snapshot.remoteReconcileState,
            )
        }

        private suspend fun materializeS3Conflicts(
            verified: VerifiedPreparedS3Sync,
            client: com.lomo.data.s3.LomoS3Client,
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            config: S3ResolvedConfig,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
        ): S3ConflictMaterialization {
            val conflictActions =
                verified.prepared.plan.actions.filter { action ->
                    action.direction == S3SyncDirection.CONFLICT
                }
            val conflictSet =
                buildS3ConflictSet(
                    actions = conflictActions,
                    client = client,
                    layout = layout,
                    config = config,
                    remoteFiles = verified.prepared.remoteFiles,
                    fileBridgeScope = fileBridgeScope,
                    mode = mode,
                    encodingSupport = encodingSupport,
                    actionConcurrency = runtime.performanceTuner.currentProfile().s3ActionConcurrency,
                    lightweightPreview = verified.prepared.conflictPreview.lightweight,
                )
            val reviewSession =
                conflictSet
                    ?.takeIf { isS3InitialImportPreview(conflictActions, verified.prepared.metadataByPath) }
                    ?.toS3InitialImportReview()
            return S3ConflictMaterialization(
                verified = verified,
                conflictSet = conflictSet.takeIf { reviewSession == null },
                reviewSession = reviewSession,
            )
        }

        private suspend fun materializeExecutionConflicts(
            verified: VerifiedPreparedS3Sync,
            execution: S3ActionExecutionResult,
            client: com.lomo.data.s3.LomoS3Client,
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            config: S3ResolvedConfig,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
        ): com.lomo.domain.model.SyncConflictSet? {
            if (execution.conflictPaths.isEmpty()) {
                return null
            }
            val conflictActions =
                verified.prepared.normalActions
                    .filter { action -> action.path in execution.conflictPaths }
                    .map { action ->
                        action.copy(
                            direction = S3SyncDirection.CONFLICT,
                            reason = S3SyncReason.CONFLICT,
                        )
                    }
            return buildS3ConflictSet(
                actions = conflictActions,
                client = client,
                layout = layout,
                config = config,
                remoteFiles = verified.prepared.remoteFiles,
                fileBridgeScope = fileBridgeScope,
                mode = mode,
                encodingSupport = encodingSupport,
                actionConcurrency = runtime.performanceTuner.currentProfile().s3ActionConcurrency,
                lightweightPreview = true,
            )
        }

    }

private data class FullSyncSnapshot(
    val localFiles: Map<String, LocalS3File>,
    val remoteFiles: Map<String, RemoteS3File>,
    val metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
)

private class S3SyncSnapshotLoader(
    private val runtime: S3SyncRepositoryContext,
    private val encodingSupport: S3SyncEncodingSupport,
    private val protocolStateStore: S3SyncProtocolStateStore,
    private val localChangeJournalStore: S3LocalChangeJournalStore,
    private val remoteIndexStore: S3RemoteIndexStore,
    private val remoteShardStateStore: S3RemoteShardStateStore,
    private val recentActivityTracker: S3RemoteRecentActivityTracker,
) {
    suspend fun loadS3PlanningSnapshot(
        client: com.lomo.data.s3.LomoS3Client,
        layout: com.lomo.data.sync.SyncDirectoryLayout,
        config: S3ResolvedConfig,
        fileBridgeScope: S3SyncFileBridgeScope,
        mode: S3LocalSyncMode,
        policy: S3SyncWorkIntent,
        protocolState: S3SyncProtocolState?,
    ): LoadedS3PlanningSnapshot =
        tryLoadIncrementalSyncSnapshot(
            client = client,
            layout = layout,
            config = config,
            fileBridgeScope = fileBridgeScope,
            mode = mode,
            policy = policy,
            protocolState = protocolState,
        ) ?: loadFullSyncPlanningSnapshot(
            client = client,
            layout = layout,
            config = config,
            fileBridgeScope = fileBridgeScope,
            protocolState = protocolState,
        )

    private suspend fun loadFullSyncPlanningSnapshot(
        client: com.lomo.data.s3.LomoS3Client,
        layout: com.lomo.data.sync.SyncDirectoryLayout,
        config: S3ResolvedConfig,
        fileBridgeScope: S3SyncFileBridgeScope,
        protocolState: S3SyncProtocolState?,
    ): LoadedS3PlanningSnapshot.Full {
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
        return LoadedS3PlanningSnapshot.Full(
            localFiles = fullSnapshot.localFiles,
            remoteFiles = fullSnapshot.remoteFiles,
            metadataByPath = fullSnapshot.metadataByPath,
            protocolState = protocolState,
            fullScanEpoch = fullScanEpoch,
        )
    }

    private suspend fun tryLoadIncrementalSyncSnapshot(
        client: com.lomo.data.s3.LomoS3Client,
        layout: com.lomo.data.sync.SyncDirectoryLayout,
        config: S3ResolvedConfig,
        fileBridgeScope: S3SyncFileBridgeScope,
        mode: S3LocalSyncMode,
        policy: S3SyncWorkIntent,
        protocolState: S3SyncProtocolState?,
    ): LoadedS3PlanningSnapshot? {
        if (policy == S3SyncWorkIntent.FULL_RECONCILE) {
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
        return loadIncrementalSyncSnapshotFromFreshIndex(
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

    private suspend fun loadIncrementalSyncSnapshotFromFreshIndex(
        client: com.lomo.data.s3.LomoS3Client,
        layout: com.lomo.data.sync.SyncDirectoryLayout,
        config: S3ResolvedConfig,
        fileBridgeScope: S3SyncFileBridgeScope,
        mode: S3LocalSyncMode,
        policy: S3SyncWorkIntent,
        protocolState: S3SyncProtocolState,
        journalEntries: Map<String, S3LocalChangeJournalEntry>,
    ): LoadedS3PlanningSnapshot {
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
            loadLocalOnlyIncrementalSyncSnapshot(
                layout = layout,
                mode = mode,
                fileBridgeScope = fileBridgeScope,
                protocolState = protocolState,
                journalEntries = journalEntries,
            )
        } else {
            loadIncrementalSyncWithReconcileSnapshot(
                layout = layout,
                fileBridgeScope = fileBridgeScope,
                protocolState = protocolState,
                journalEntries = journalEntries,
                journalEntriesByPath = journalEntriesByPath,
                remoteReconcileState = remoteReconcileState,
            )
        }
    }

    private suspend fun loadLocalOnlyIncrementalSyncSnapshot(
        layout: com.lomo.data.sync.SyncDirectoryLayout,
        mode: S3LocalSyncMode,
        fileBridgeScope: S3SyncFileBridgeScope,
        protocolState: S3SyncProtocolState,
        journalEntries: Map<String, S3LocalChangeJournalEntry>,
    ): LoadedS3PlanningSnapshot.LocalOnlyIncremental {
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
        return LoadedS3PlanningSnapshot.LocalOnlyIncremental(
            protocolState = protocolState,
            journalEntries = journalEntries,
            initialPreparation = initialPreparation,
        )
    }

    private suspend fun loadIncrementalSyncWithReconcileSnapshot(
        layout: com.lomo.data.sync.SyncDirectoryLayout,
        fileBridgeScope: S3SyncFileBridgeScope,
        protocolState: S3SyncProtocolState,
        journalEntries: Map<String, S3LocalChangeJournalEntry>,
        journalEntriesByPath: Map<String, S3LocalChangeJournalEntry>,
        remoteReconcileState: PreparedRemoteReconcile,
    ): LoadedS3PlanningSnapshot.ReconcileIncremental {
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
        return LoadedS3PlanningSnapshot.ReconcileIncremental(
            protocolState = protocolState,
            journalEntries = journalEntries,
            journalEntriesByPath = journalEntriesByPath,
            remoteReconcileState = remoteReconcileState,
            candidatePaths = candidatePaths,
            reconcileInputs = reconcileInputs,
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

private sealed interface LoadedS3PlanningSnapshot {
    val localFiles: Map<String, LocalS3File>
    val remoteFiles: Map<String, RemoteS3File>
    val metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>
    val protocolState: S3SyncProtocolState?

    data class Full(
        override val localFiles: Map<String, LocalS3File>,
        override val remoteFiles: Map<String, RemoteS3File>,
        override val metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
        override val protocolState: S3SyncProtocolState?,
        val fullScanEpoch: Long,
    ) : LoadedS3PlanningSnapshot

    data class LocalOnlyIncremental(
        override val protocolState: S3SyncProtocolState,
        val journalEntries: Map<String, S3LocalChangeJournalEntry>,
        val initialPreparation: S3IncrementalPreparation,
    ) : LoadedS3PlanningSnapshot {
        override val localFiles: Map<String, LocalS3File> = initialPreparation.localFiles
        override val remoteFiles: Map<String, RemoteS3File> = initialPreparation.remoteFiles
        override val metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity> =
            initialPreparation.metadataByPath
    }

    data class ReconcileIncremental(
        override val protocolState: S3SyncProtocolState,
        val journalEntries: Map<String, S3LocalChangeJournalEntry>,
        val journalEntriesByPath: Map<String, S3LocalChangeJournalEntry>,
        val remoteReconcileState: PreparedRemoteReconcile,
        val candidatePaths: Set<String>,
        val reconcileInputs: IncrementalReconcileInputs,
    ) : LoadedS3PlanningSnapshot {
        override val localFiles: Map<String, LocalS3File> = reconcileInputs.localFiles
        override val remoteFiles: Map<String, RemoteS3File> = reconcileInputs.remoteFiles
        override val metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity> =
            reconcileInputs.plannerMetadataByPath
    }
}

private data class S3SyncLifecycleSnapshot(
    val client: com.lomo.data.s3.LomoS3Client,
    val loaded: LoadedS3PlanningSnapshot,
    val localFiles: Map<String, LocalS3File> = loaded.localFiles,
    val remoteFiles: Map<String, RemoteS3File> = loaded.remoteFiles,
    val metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity> = loaded.metadataByPath,
)

private data class S3ConflictMaterialization(
    val verified: VerifiedPreparedS3Sync,
    val conflictSet: com.lomo.domain.model.SyncConflictSet?,
    val reviewSession: com.lomo.domain.model.SyncReviewSession?,
)

private data class S3CommittedSync(
    val result: S3SyncResult,
    val execution: S3ActionExecutionResult,
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
    actionConcurrency: Int,
    largeTransferConcurrency: Int,
): S3ActionExecutionResult =
    coroutineScope {
        val smallLaneLimiter = Semaphore(actionConcurrency.coercePositiveConcurrency())
        val largeLaneLimiter = Semaphore(largeTransferConcurrency.coercePositiveConcurrency())
        val indexedResults =
            verified.prepared.normalActions.mapIndexed { index, action ->
                async {
                    withS3ActionLanePermit(
                        action = action,
                        localFiles = verified.prepared.localFiles,
                        remoteFiles = verified.prepared.remoteFiles,
                        metadataByPath = verified.prepared.metadataByPath,
                        smallLaneLimiter = smallLaneLimiter,
                        largeLaneLimiter = largeLaneLimiter,
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
                                    observedMissingRemotePaths = verified.prepared.observedMissingRemotePaths,
                                ),
                        )
                    }
                }
            }.awaitAll().sortedBy(IndexedS3ActionExecutionResult::index)

        val actionOutcomes = mutableMapOf<String, Pair<S3SyncDirection, S3SyncReason>>()
        val syncedContentFingerprints = mutableMapOf<String, String>()
        val failedPaths = mutableListOf<String>()
        val unresolvedPaths = mutableSetOf<String>()
        val conflictPaths = mutableSetOf<String>()
        val localFilesAfterSync = verified.prepared.localFiles.toMutableMap()
        val remoteFilesAfterSync = verified.prepared.remoteFiles.toMutableMap()
        var localChanged = false
        var memoRefreshPlan: S3MemoRefreshPlan = S3MemoRefreshPlan.None

        indexedResults.forEach { execution ->
            when (val result = execution.state) {
                S3ActionExecutionState.Skipped -> {
                    unresolvedPaths += execution.action.path
                }

                is S3ActionExecutionState.Conflict -> {
                    unresolvedPaths += execution.action.path
                    conflictPaths += execution.action.path
                    actionOutcomes[execution.action.path] =
                        S3SyncDirection.CONFLICT to S3SyncReason.CONFLICT
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
            conflictPaths = conflictPaths,
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
    return verified.copy(
        prepared =
            verified.prepared.copy(
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
    hasMaterializedConflict: Boolean,
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
        hasMaterializedConflict = hasMaterializedConflict,
        now = now,
    )
}

private fun List<S3SyncAction>.toRemoteSyncActionTelemetry(): RemoteSyncActionTelemetry =
    RemoteSyncActionTelemetry(
        total = size,
        upload = count { action -> action.direction == S3SyncDirection.UPLOAD },
        download = count { action -> action.direction == S3SyncDirection.DOWNLOAD },
        deleteLocal = count { action -> action.direction == S3SyncDirection.DELETE_LOCAL },
        deleteRemote = count { action -> action.direction == S3SyncDirection.DELETE_REMOTE },
        conflict = count { action -> action.direction == S3SyncDirection.CONFLICT },
    )
