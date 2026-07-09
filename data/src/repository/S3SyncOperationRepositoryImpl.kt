package com.lomo.data.repository

import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.sync.S3SyncWorkPolicyPlanner
import com.lomo.data.sync.SyncRefreshCoalescer
import com.lomo.data.sync.SyncRefreshSignal
import com.lomo.data.sync.SyncScheduledWork
import com.lomo.data.sync.SyncWorkDecision
import com.lomo.data.sync.SyncWorkPayload
import com.lomo.data.sync.parseS3AutoSyncInterval
import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.S3SyncFailureException
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.S3SyncStatus
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.repository.S3SyncOperationRepository


import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class S3SyncOperationRepositoryImpl internal constructor(
    private val syncExecutor: S3SyncExecutor,
    private val statusTester: S3SyncStatusTester,
    private val refreshPolicyPlanner: S3RefreshSyncPolicyPlanner,
    private val scheduledWorkEnqueuer: S3ScheduledSyncWorkEnqueuer,
    private val stateHolder: S3SyncStateHolder,
    private val pendingConflictStore: PendingSyncConflictStore,
) : S3SyncOperationRepository,
    S3SyncWorkExecutor {
        private val syncExecutionGate =
            SyncExecutionGate<S3SyncResult>(
                defaultInProgressResult = { S3SyncResult.Success("S3 sync already in progress") },
            )
        private var refreshCoalescer = SyncRefreshCoalescer()

        internal constructor(
            syncExecutor: S3SyncExecutor,
            statusTester: S3SyncStatusTester,
            refreshPolicyPlanner: S3RefreshSyncPolicyPlanner,
            scheduledWorkEnqueuer: S3ScheduledSyncWorkEnqueuer,
            stateHolder: S3SyncStateHolder,
            pendingConflictStore: PendingSyncConflictStore,
            nowProvider: () -> Long,
        ) : this(
            syncExecutor = syncExecutor,
            statusTester = statusTester,
            refreshPolicyPlanner = refreshPolicyPlanner,
            scheduledWorkEnqueuer = scheduledWorkEnqueuer,
            stateHolder = stateHolder,
            pendingConflictStore = pendingConflictStore,
        ) {
            this.refreshCoalescer = SyncRefreshCoalescer(nowProvider = nowProvider)
        }

        override suspend fun sync(): S3SyncResult =
            executeS3Sync(S3SyncWorkIntent.FAST_THEN_RECONCILE)

        override suspend fun executeS3Sync(intent: S3SyncWorkIntent): S3SyncResult =
            withSyncGuard(inProgressMessage = "S3 sync already in progress") {
                restorePendingConflictIfPresent()?.let { pending ->
                    return@withSyncGuard pending
                }
                val result = syncExecutor.performSync(intent)
                clearPendingConflictsOnSuccess(result)
                result
            }

        override suspend fun syncForRefresh(): S3SyncResult {
            val initialSignal =
                refreshCoalescer.beginRefreshRequest()
                    ?: return S3SyncResult.Success("S3 refresh sync already in progress")
            return try {
                executeRefreshSyncLoop(initialSignal)
            } finally {
                refreshCoalescer.finishRefreshLoop()
            }
        }

        override suspend fun getStatus(): S3SyncStatus = statusTester.getStatus()

        override suspend fun testConnection(): S3SyncResult = statusTester.testConnection()

        private suspend fun withSyncGuard(
            inProgressMessage: String,
            block: suspend () -> S3SyncResult,
        ): S3SyncResult =
            syncExecutionGate.run(
                inProgressResult = { S3SyncResult.Success(inProgressMessage) },
                block = block,
            )

        private suspend fun restorePendingConflictIfPresent(): S3SyncResult? {
            return restorePendingConflict(
                pendingConflictStore = pendingConflictStore,
                backendType = SyncBackendType.S3,
                restorer = syncExecutor::restorePendingConflict,
                onRestored = { pending -> stateHolder.state.value = S3SyncState.ConflictDetected(pending) },
                asResult = { pending -> S3SyncResult.Conflict("Pending conflicts remain", pending) },
                asInvalidatedResult = { reason ->
                    S3SyncResult.Error("Pending S3 conflict session requires rebuild: $reason")
                },
                asFailedResult = { error ->
                    S3SyncResult.Error(
                        message = "Pending S3 conflict session restore failed: ${error.category}",
                        exception = error.cause,
                    )
                },
            )
        }

        private suspend fun clearPendingConflictsOnSuccess(result: S3SyncResult) {
            clearPendingConflictOnSuccess(
                pendingConflictStore = pendingConflictStore,
                backendType = SyncBackendType.S3,
                result = result,
                isSuccess = { candidate -> candidate is S3SyncResult.Success },
            )
        }

        private suspend fun executeRefreshSyncLoop(initialSignal: SyncRefreshSignal): S3SyncResult {
            var currentSignal = initialSignal
            var firstResult: S3SyncResult? = null
            while (true) {
                val result =
                    withSyncGuard(inProgressMessage = "S3 sync already in progress") {
                        restorePendingConflictIfPresent()?.let { pending ->
                            return@withSyncGuard pending
                        }
                        val decision = refreshPolicyPlanner.planRefreshSync(currentSignal)
                        val syncResult = syncExecutor.performSync(decision.requireS3ForegroundPolicy())
                        if (syncResult is S3SyncResult.Success) {
                            scheduledWorkEnqueuer.enqueue(decision.scheduledWork)
                        }
                        clearPendingConflictsOnSuccess(syncResult)
                        syncResult
                    }
                if (firstResult == null) {
                    firstResult = result
                }
                if (result !is S3SyncResult.Success) {
                    stateHolder.state.value = result.stateAfterRefresh(System.currentTimeMillis())
                    return result
                }
                currentSignal = refreshCoalescer.consumePendingRefreshSignal() ?: return firstResult
            }
        }
    }

internal interface S3RefreshSyncPolicyPlanner {
    suspend fun planRefreshSync(signal: SyncRefreshSignal): SyncWorkDecision
}

internal interface S3ScheduledSyncWorkEnqueuer {
    suspend fun enqueue(work: List<SyncScheduledWork>)
}

internal class DefaultS3RefreshSyncPolicyPlanner(
    private val runtime: S3SyncRepositoryContext,
    private val policyPlanner: S3SyncWorkPolicyPlanner,
) : S3RefreshSyncPolicyPlanner {
        override suspend fun planRefreshSync(signal: SyncRefreshSignal): SyncWorkDecision {
            val interval = runtime.dataStore.s3AutoSyncInterval.first()
            return policyPlanner.planRefresh(
                reconcileInterval = parseS3AutoSyncInterval(interval),
                signal = signal,
            )
        }
    }



private fun SyncWorkDecision.requireS3ForegroundPolicy(): S3SyncWorkIntent {
    val foregroundWork = foregroundWork
        ?: error("S3 refresh policy must emit foreground work")
    return when (val payload = foregroundWork.payload) {
        is SyncWorkPayload.ProviderParameters ->
            payload.values[S3_SYNC_WORK_INTENT_PARAMETER]
                ?.let { raw -> enumValues<S3SyncWorkIntent>().firstOrNull { candidate -> candidate.name == raw } }
                ?: error("S3 refresh policy emitted provider payload without S3 work intent")
        SyncWorkPayload.StandardRemoteSync -> error("S3 refresh policy emitted standard remote payload")
    }
}

class S3SyncStatusTester(
    private val runtime: S3SyncRepositoryContext,
    private val support: S3SyncRepositorySupport,
    private val encodingSupport: S3SyncEncodingSupport,
    private val fileBridge: S3SyncFileBridge,
    private val protocolStateStore: S3SyncProtocolStateStore,
    private val localChangeJournalStore: S3LocalChangeJournalStore,
    private val remoteIndexStore: S3RemoteIndexStore,
    private val remoteShardStateStore: S3RemoteShardStateStore,
) {
        private val objectKeyPolicy = S3RemoteObjectKeyPolicy(encodingSupport)

        suspend fun getStatus(): S3SyncStatus {
            val config = support.resolveConfig() ?: return S3SyncStatus(0, 0, 0, null)
            val layout = SyncDirectoryLayout.resolve(runtime.dataStore)
            val mode = resolveLocalSyncMode(runtime)
            val fileBridgeScope = fileBridge.modeAware(mode)
            return support.withClient(config) { client ->
                runtime.stateHolder.state.value = S3SyncState.Listing
                prepareIncrementalStatus(
                    client = client,
                    layout = layout,
                    mode = mode,
                    fileBridgeScope = fileBridgeScope,
                    config = config,
                )?.let { status ->
                    return@withClient status
                }
                val (localFiles, remoteFiles, metadata) =
                    coroutineScope {
                        val localFilesDeferred = async { fileBridgeScope.localFiles(layout) }
                        val remoteFilesDeferred = async { fileBridgeScope.remoteFiles(client, layout, config) }
                        val metadataDeferred =
                            async {
                                runtime.metadataDao.readAllPlannerMetadataByPath()
                            }
                        Triple(
                            localFilesDeferred.await(),
                            remoteFilesDeferred.await(),
                            metadataDeferred.await(),
                        )
                    }
                val plan =
                    runtime.planner.plan(
                        localFiles = localFiles.toS3RemoteSyncLocalSnapshots(),
                        remoteFiles = remoteFiles.toS3RemoteSyncRemoteSnapshots(),
                        metadata = metadata.toS3RemoteSyncMetadataSnapshots(),
                    )
                S3SyncStatus(
                    remoteFileCount = remoteFiles.size,
                    localFileCount = localFiles.size,
                    pendingChanges = plan.pendingChanges,
                    lastSyncTime = runtime.dataStore.s3LastSyncTime.first().takeIf { it > 0L },
                )
            }
        }

        private suspend fun prepareIncrementalStatus(
            client: com.lomo.data.s3.LomoS3Client,
            layout: SyncDirectoryLayout,
            mode: S3LocalSyncMode,
            fileBridgeScope: S3SyncFileBridgeScope,
            config: S3ResolvedConfig,
        ): S3SyncStatus? {
            val protocolState =
                protocolStateStore.read()
                    ?.takeIf {
                        protocolStateStore.incrementalSyncEnabled &&
                            localChangeJournalStore.incrementalSyncEnabled &&
                            it.protocolVersion == S3_INCREMENTAL_PROTOCOL_VERSION &&
                            it.localModeFingerprint.compatibleWith(mode)
            } ?: return null
            val lastSyncTime = runtime.dataStore.s3LastSyncTime.first().takeIf { it > 0L }
            val localAuditExpired = shouldPerformFullLocalAudit(mode, protocolState)
            val effectiveLocalChanges =
                resolveEffectiveLocalChangeSet(
                    journalEntries = localChangeJournalStore.read(),
                    layout = layout,
                    mode = mode,
                    fileBridgeScope = fileBridgeScope,
                    metadataDao = runtime.metadataDao,
                    boundedLocalAudit = localAuditExpired,
                    localAuditCursor = protocolState.localAuditCursor,
                )
            val journalEntries = effectiveLocalChanges.journalEntries
            if (!protocolState.hasFreshRemoteIndex(config) || localAuditExpired) {
                return prepareBoundedReconcileStatus(
                    client = client,
                    layout = layout,
                    mode = mode,
                    fileBridgeScope = fileBridgeScope,
                    config = config,
                    protocolState = protocolState,
                    journalEntries = journalEntries,
                    effectiveLocalChanges = effectiveLocalChanges,
                    lastSyncTime = lastSyncTime,
                )
            }
            if (journalEntries.isEmpty()) {
                val indexedRemoteCount =
                    if (remoteIndexStore.remoteIndexEnabled) {
                        remoteIndexStore.readPresentCount()
                    } else {
                        protocolState.indexedRemoteFileCount
                    }
                return S3SyncStatus(
                    remoteFileCount = indexedRemoteCount,
                    localFileCount = effectiveLocalChanges.currentLocalFileCount ?: protocolState.indexedLocalFileCount,
                    pendingChanges = 0,
                    lastSyncTime = lastSyncTime,
                )
            }
            val localOnly =
                prepareLocalOnlyIncrementalSync(
                    journalEntries = journalEntries,
                    layout = layout,
                    mode = mode,
                    fileBridgeScope = fileBridgeScope,
                    planner = runtime.planner,
                    metadataDao = runtime.metadataDao,
                    remoteIndexStore = remoteIndexStore,
                )
            val indexedRemoteCount =
                if (remoteIndexStore.remoteIndexEnabled) {
                    remoteIndexStore.readPresentCount()
                } else {
                    protocolState.indexedRemoteFileCount
                }
            return S3SyncStatus(
                remoteFileCount = indexedRemoteCount,
                localFileCount = effectiveLocalChanges.currentLocalFileCount ?: protocolState.indexedLocalFileCount,
                pendingChanges = localOnly.plan.pendingChanges,
                lastSyncTime = lastSyncTime,
            )
        }

        private suspend fun prepareBoundedReconcileStatus(
            client: com.lomo.data.s3.LomoS3Client,
            layout: SyncDirectoryLayout,
            mode: S3LocalSyncMode,
            fileBridgeScope: S3SyncFileBridgeScope,
            config: S3ResolvedConfig,
            protocolState: S3SyncProtocolState,
            journalEntries: Map<String, S3LocalChangeJournalEntry>,
            effectiveLocalChanges: S3EffectiveLocalChangeSet,
            lastSyncTime: Long?,
        ): S3SyncStatus? {
            val journalEntriesByPath = journalEntries.resolvePaths(layout, mode)
            val remoteReconcileState =
                prepareRemoteReconcile(
                    client = client,
                    layout = layout,
                    config = config,
                    mode = mode,
                    protocolState = protocolState,
                    encodingSupport = encodingSupport,
                    objectKeyPolicy = objectKeyPolicy,
                    metadataDao = runtime.metadataDao,
                    remoteIndexStore = remoteIndexStore,
                    shardStateStore = remoteShardStateStore,
                )
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
                    localFiles = reconcileInputs.localFiles.toS3RemoteSyncLocalSnapshots(),
                    remoteFiles = reconcileInputs.remoteFiles.toS3RemoteSyncRemoteSnapshots(),
                    metadata = reconcileInputs.plannerMetadataByPath.toS3RemoteSyncMetadataSnapshots(),
                    missingRemoteVerificationByPath =
                        remoteReconcileState.missingRemotePaths.associateWith {
                            S3RemoteVerificationLevel.VERIFIED_REMOTE
                        }.toS3RemoteSyncRemoteAbsenceVerifications(),
                    defaultMissingRemoteVerification =
                        S3RemoteVerificationLevel.UNKNOWN_REMOTE.toRemoteSyncRemoteAbsenceVerification(),
                )
            return S3SyncStatus(
                remoteFileCount = boundedStatusRemoteCount(protocolState, remoteReconcileState),
                localFileCount = effectiveLocalChanges.currentLocalFileCount ?: protocolState.indexedLocalFileCount,
                pendingChanges = plan.pendingChanges,
                lastSyncTime = lastSyncTime,
            )
        }

        private suspend fun boundedStatusRemoteCount(
            protocolState: S3SyncProtocolState,
            remoteReconcileState: PreparedRemoteReconcile,
        ): Int {
            val existingByPath =
                remoteIndexStore
                    .readByRelativePaths(remoteReconcileState.candidatePaths)
                    .associateBy(S3RemoteIndexEntry::relativePath)
            val observedNewCount =
                remoteReconcileState.remoteFiles.keys.count { path ->
                    existingByPath[path]?.missingOnLastScan != false
                }
            val missingKnownCount =
                remoteReconcileState.missingRemotePaths.count { path ->
                    existingByPath[path]?.missingOnLastScan == false
                }
            return (protocolState.indexedRemoteFileCount + observedNewCount - missingKnownCount).coerceAtLeast(0)
        }

        suspend fun testConnection(): S3SyncResult {
            val config = support.resolveConfig() ?: return support.notConfiguredResult()
            return runNonFatalCatching {
                support.withClient(config) { client ->
                    val prefix = encodingSupport.remoteKeyPrefix(config)
                    val layout = SyncDirectoryLayout.resolve(runtime.dataStore)
                    val mode = resolveLocalSyncMode(runtime)
                    verifyAccessWithTimeout(client, prefix)
                    validateListingCompatibility(client, prefix, config, layout, mode)
                    S3SyncResult.Success("S3 connection successful")
                }
            }.getOrElse(support::mapConnectionTestError)
        }

        private suspend fun validateListingCompatibility(
            client: com.lomo.data.s3.LomoS3Client,
            prefix: String,
            config: S3ResolvedConfig,
            layout: SyncDirectoryLayout,
            mode: S3LocalSyncMode,
        ) {
            val sampledKeys = sampleRemoteKeys(client, prefix)
            val sampledAnalysis = analyzeRemoteKeys(sampledKeys, prefix, config, layout, mode)
            if (sampledAnalysis.hasCompatibleKey) {
                return
            }
            val (remoteKeys, analysis) =
                if (sampledKeys.size == S3_CONNECTION_TEST_SAMPLE_LIMIT) {
                    val allKeys = allRemoteKeys(client, prefix)
                    allKeys to analyzeRemoteKeys(allKeys, prefix, config, layout, mode)
                } else {
                    sampledKeys to sampledAnalysis
                }
            if (analysis.hasCompatibleKey) {
                return
            }
            if (analysis.firstInvalidKey != null) {
                throw S3SyncFailureException(
                    code = S3SyncErrorCode.ENCRYPTION_FAILED,
                    message =
                        incompatibleEncryptedListingMessage(
                            config = config,
                        ),
                    cause = analysis.lastDecodeError,
                )
            }
            if (remoteKeys.isNotEmpty()) {
                throw S3SyncFailureException(
                    code = S3SyncErrorCode.ENCRYPTION_FAILED,
                    message = vaultRootMismatchMessage(),
                )
            }
        }

        private suspend fun analyzeRemoteKeys(
            remoteKeys: List<String>,
            prefix: String,
            config: S3ResolvedConfig,
            layout: SyncDirectoryLayout,
            mode: S3LocalSyncMode,
        ): ListingCompatibilityAnalysis {
            var firstInvalidKey: String? = null
            var lastDecodeError: Throwable? = null
            for (remoteKey in remoteKeys) {
                val rawRelativePath = remoteKey.removePrefix(prefix)
                val decoded =
                    when (config.encryptionMode) {
                        S3EncryptionMode.NONE -> rawRelativePath
                        else ->
                            runNonFatalCatching {
                                encodingSupport.decodeRelativePath(remoteKey, config)
                            }.onFailure { error ->
                                val ignored =
                                    isObviousPlaintextExternalPathForEncryptedConnectionCheck(
                                        rawRelativePath,
                                        layout,
                                        mode,
                                    )
                                if (!ignored && firstInvalidKey == null) {
                                    firstInvalidKey = remoteKey
                                }
                                lastDecodeError = error
                            }.getOrNull()
                    }
                if (!decoded.isNullOrBlank() &&
                    !isIgnoredExternalPathForConnectionCheck(decoded, layout, mode)
                ) {
                    return ListingCompatibilityAnalysis(hasCompatibleKey = true)
                }
            }
            return ListingCompatibilityAnalysis(
                hasCompatibleKey = false,
                firstInvalidKey = firstInvalidKey,
                lastDecodeError = lastDecodeError,
            )
        }

        private suspend fun sampleRemoteKeys(
            client: com.lomo.data.s3.LomoS3Client,
            prefix: String,
        ): List<String> =
            listRemoteKeys(
                client = client,
                prefix = prefix,
                maxKeys = S3_CONNECTION_TEST_SAMPLE_LIMIT,
                phase = "listing sampled remote keys",
            )

        private suspend fun allRemoteKeys(
            client: com.lomo.data.s3.LomoS3Client,
            prefix: String,
        ): List<String> =
            listRemoteKeys(
                client = client,
                prefix = prefix,
                maxKeys = null,
                phase = "scanning remote keys",
            )

        private suspend fun listRemoteKeys(
            client: com.lomo.data.s3.LomoS3Client,
            prefix: String,
            maxKeys: Int?,
            phase: String,
        ): List<String> =
            withTimeoutOrNull(S3_CONNECTION_TEST_TIMEOUT_MS) {
                client.listKeys(prefix = prefix, maxKeys = maxKeys)
            } ?: throw S3SyncFailureException(
                code = S3SyncErrorCode.CONNECTION_FAILED,
                message = connectionTimeoutMessage(phase),
            )

        private suspend fun verifyAccessWithTimeout(
            client: com.lomo.data.s3.LomoS3Client,
            prefix: String,
        ) {
            val completed =
                withTimeoutOrNull(S3_CONNECTION_TEST_TIMEOUT_MS) {
                    client.verifyAccess(prefix)
                    true
                }
            if (completed != true) {
                throw S3SyncFailureException(
                    code = S3SyncErrorCode.CONNECTION_FAILED,
                    message = connectionTimeoutMessage("verifying bucket access"),
                )
            }
        }
}

private data class ListingCompatibilityAnalysis(
    val hasCompatibleKey: Boolean,
    val firstInvalidKey: String? = null,
    val lastDecodeError: Throwable? = null,
)

private fun connectionTimeoutMessage(
    phase: String,
): String =
    "S3 connection test timed out after " +
        "${S3_CONNECTION_TEST_TIMEOUT_MS / MILLIS_PER_SECOND}s while $phase. " +
        "Check the endpoint URL, region, addressing style, and TLS/network connectivity."

private fun incompatibleEncryptedListingMessage(
    config: S3ResolvedConfig,
): String =
    "No ${config.encryptionMode.name}-compatible object names were found in the configured S3 sync scope. " +
        "Check the S3 prefix, encryption mode, and encryption password. " +
        "If this bucket contains plaintext objects or data written by another tool/config, point Lomo at the exact Remotely Save prefix."

private fun vaultRootMismatchMessage(): String =
    "The configured S3 remote root does not appear to match Lomo's current content sync scope. " +
        "Only markdown files and supported attachments are considered syncable; hidden and system paths are ignored."

private const val S3_CONNECTION_TEST_TIMEOUT_MS = 15_000L
private const val S3_CONNECTION_TEST_SAMPLE_LIMIT = 32
private const val MILLIS_PER_SECOND = 1_000L
