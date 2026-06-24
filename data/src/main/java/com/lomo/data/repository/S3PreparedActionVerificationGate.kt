package com.lomo.data.repository

import com.lomo.domain.model.S3SyncDirection
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class S3PreparedActionVerificationGate(
    private val planner: S3SyncPlanner,
    private val encodingSupport: S3SyncEncodingSupport,
    private val objectKeyPolicy: S3RemoteObjectKeyPolicy,
    private val performanceTuner: SyncPerformanceTuner = DisabledSyncPerformanceTuner,
    private val remoteIndexStore: S3RemoteIndexStore,
) {
    suspend fun verify(
        prepared: PreparedS3Sync,
        client: com.lomo.data.s3.LomoS3Client,
        config: S3ResolvedConfig,
        layout: com.lomo.data.sync.SyncDirectoryLayout? = null,
        fileBridgeScope: S3SyncFileBridgeScope? = null,
        mode: S3LocalSyncMode? = null,
    ): VerifiedPreparedS3Sync {
        val selection =
            loadVerificationSelection(prepared = prepared, config = config)
                ?: return VerifiedPreparedS3Sync(prepared = prepared)
        val verifiedRemoteFiles = prepared.remoteFiles.toMutableMap()
        val observedMissingRemotePaths = linkedSetOf<String>()
        loadVerificationResults(
            pathsToVerify = selection.pathsToVerify,
            actionsByPath = selection.actionsByPath,
            prepared = prepared,
            client = client,
            config = config,
        ).forEach { result ->
            applyVerificationResult(
                result = result,
                prepared = prepared,
                existingRemoteIndexByPath = selection.existingRemoteIndexByPath,
                verifiedRemoteFiles = verifiedRemoteFiles,
                verifiedMissingRemotePaths = selection.verifiedMissingRemotePaths,
                observedMissingRemotePaths = observedMissingRemotePaths,
            )
        }
        val replannedPaths = (selection.pathsToVerify + selection.verifiedMissingRemotePaths).toSortedSet()
        val initialReplannedActions =
            planner.planPaths(
                paths = replannedPaths,
                localFiles = prepared.localFiles.toS3RemoteSyncLocalSnapshots(),
                remoteFiles = verifiedRemoteFiles.toS3RemoteSyncRemoteSnapshots(),
                metadata = prepared.metadataByPath.toS3RemoteSyncMetadataSnapshots(),
                preResolvedActionsByPath =
                    prepared.preResolvedActionsByPath
                        .filterKeys(replannedPaths::contains)
                        .toS3RemoteSyncActions(),
                missingRemoteVerificationByPath =
                    selection.verifiedMissingRemotePaths.associateWith {
                        S3RemoteVerificationLevel.VERIFIED_REMOTE
                    }.toS3RemoteSyncRemoteAbsenceVerifications(),
                defaultMissingRemoteVerification =
                    S3RemoteVerificationLevel.UNKNOWN_REMOTE.toRemoteSyncRemoteAbsenceVerification(),
            ).toS3Plan()
        val replannedActions =
            if (layout != null && fileBridgeScope != null && mode != null) {
                refineTrackedMemoPlanWithContent(
                    plan = initialReplannedActions,
                    localFiles = prepared.localFiles,
                    remoteFiles = verifiedRemoteFiles,
                    metadataByPath = prepared.metadataByPath,
                    layout = layout,
                    mode = mode,
                    localFingerprintSource = fileBridgeScope.localFingerprintSource(layout),
                ).actions
            } else {
                initialReplannedActions.actions
            }
        return VerifiedPreparedS3Sync(
            prepared =
                prepared.rebuildAfterVerification(
                    verifiedPaths = replannedPaths,
                    verifiedRemoteFiles = verifiedRemoteFiles,
                    replannedActions = replannedActions,
                    observedMissingRemotePaths = observedMissingRemotePaths,
                ),
            verifiedMissingRemotePaths = selection.verifiedMissingRemotePaths,
        )
    }

    private suspend fun loadVerificationSelection(
        prepared: PreparedS3Sync,
        config: S3ResolvedConfig,
    ): S3PreparedVerificationSelection? {
        val candidatePaths =
            prepared.normalActions
                .asSequence()
                .filter(S3RemoteVerificationPolicy::requiresPreparedVerification)
                .map(S3SyncAction::path)
                .toSortedSet()
        if (candidatePaths.isEmpty()) return null
        val actionsByPath = prepared.normalActions.associateBy(S3SyncAction::path)
        val existingRemoteIndexByPath = loadExistingRemoteIndexByPath(candidatePaths)
        validateExistingRemoteIndexKeys(entries = existingRemoteIndexByPath.values, config = config)
        val pathSelection =
            selectPathsForVerification(
                candidatePaths = candidatePaths,
                actionsByPath = actionsByPath,
                prepared = prepared,
                existingRemoteIndexByPath = existingRemoteIndexByPath,
                config = config,
            )
        if (pathSelection.pathsToVerify.isEmpty() && pathSelection.verifiedMissingRemotePaths.isEmpty()) return null
        return S3PreparedVerificationSelection(
            actionsByPath = actionsByPath,
            existingRemoteIndexByPath = existingRemoteIndexByPath,
            pathsToVerify = pathSelection.pathsToVerify,
            verifiedMissingRemotePaths = pathSelection.verifiedMissingRemotePaths,
        )
    }

    private suspend fun loadExistingRemoteIndexByPath(
        candidatePaths: Set<String>,
    ): Map<String, S3RemoteIndexEntry> =
        if (remoteIndexStore.remoteIndexEnabled) {
            remoteIndexStore
                .readByRelativePaths(candidatePaths)
                .associateBy(S3RemoteIndexEntry::relativePath)
        } else {
            emptyMap()
        }

    private fun validateExistingRemoteIndexKeys(
        entries: Collection<S3RemoteIndexEntry>,
        config: S3ResolvedConfig,
    ) {
        entries.forEach { entry ->
            objectKeyPolicy.validatedExistingKey(entry.remotePath, config)
        }
    }

    private fun selectPathsForVerification(
        candidatePaths: Set<String>,
        actionsByPath: Map<String, S3SyncAction>,
        prepared: PreparedS3Sync,
        existingRemoteIndexByPath: Map<String, S3RemoteIndexEntry>,
        config: S3ResolvedConfig,
    ): S3VerificationPathSelection {
        val verifiedMissingRemotePaths = linkedSetOf<String>()
        val pathsToVerify =
            candidatePaths.filterTo(linkedSetOf()) { path ->
                val action = actionsByPath[path] ?: return@filterTo false
                if (
                    S3RemoteVerificationPolicy.stableMissingEvidenceVerified(
                        action = action,
                        prepared = prepared,
                        remoteIndexEntry = existingRemoteIndexByPath[path],
                    )
                ) {
                    verifiedMissingRemotePaths += path
                    return@filterTo false
                }
                S3RemoteVerificationPolicy.shouldHeadPreparedPath(
                    action = action,
                    prepared = prepared,
                    remoteIndexEntry = existingRemoteIndexByPath[path],
                    config = config,
                )
            }
        return S3VerificationPathSelection(
            pathsToVerify = pathsToVerify,
            verifiedMissingRemotePaths = verifiedMissingRemotePaths,
        )
    }

    private suspend fun loadVerificationResults(
        pathsToVerify: Set<String>,
        actionsByPath: Map<String, S3SyncAction>,
        prepared: PreparedS3Sync,
        client: com.lomo.data.s3.LomoS3Client,
        config: S3ResolvedConfig,
    ): List<S3VerificationResult> {
        val verificationLimiter =
            Semaphore(performanceTuner.currentProfile().s3VerificationConcurrency.coercePositiveConcurrency())
        return coroutineScope {
            pathsToVerify.map { path ->
                async {
                    val action = requireNotNull(actionsByPath[path])
                    val remoteKey =
                        objectKeyPolicy.resolveOperationKey(
                            relativePath = path,
                            config = config,
                            remoteFile = prepared.remoteFiles[path],
                            metadata = prepared.metadataByPath[path],
                        )
                    verificationLimiter.withPermit {
                        S3VerificationResult(
                            path = path,
                            action = action,
                            verifiedRemote =
                                client
                                    .getObjectMetadata(remoteKey.value)
                                    ?.toVerifiedRemoteFile(path, encodingSupport),
                        )
                    }
                }
            }.awaitAll()
        }
    }

    private fun applyVerificationResult(
        result: S3VerificationResult,
        prepared: PreparedS3Sync,
        existingRemoteIndexByPath: Map<String, S3RemoteIndexEntry>,
        verifiedRemoteFiles: MutableMap<String, RemoteS3File>,
        verifiedMissingRemotePaths: LinkedHashSet<String>,
        observedMissingRemotePaths: LinkedHashSet<String>,
    ) {
        if (result.verifiedRemote == null) {
            verifiedRemoteFiles.remove(result.path)
            observedMissingRemotePaths += result.path
            if (
                S3RemoteVerificationPolicy.stableMissingEvidenceVerified(
                    action = result.action,
                    prepared = prepared,
                    remoteIndexEntry = existingRemoteIndexByPath[result.path],
                )
            ) {
                verifiedMissingRemotePaths += result.path
            }
            return
        }
        verifiedRemoteFiles[result.path] = result.verifiedRemote
        verifiedMissingRemotePaths.remove(result.path)
        observedMissingRemotePaths.remove(result.path)
    }
}

private data class S3VerificationResult(
    val path: String,
    val action: S3SyncAction,
    val verifiedRemote: RemoteS3File?,
)

private data class S3PreparedVerificationSelection(
    val actionsByPath: Map<String, S3SyncAction>,
    val existingRemoteIndexByPath: Map<String, S3RemoteIndexEntry>,
    val pathsToVerify: LinkedHashSet<String>,
    val verifiedMissingRemotePaths: LinkedHashSet<String>,
)

private data class S3VerificationPathSelection(
    val pathsToVerify: LinkedHashSet<String>,
    val verifiedMissingRemotePaths: LinkedHashSet<String>,
)

private fun PreparedS3Sync.rebuildAfterVerification(
    verifiedPaths: Set<String>,
    verifiedRemoteFiles: Map<String, RemoteS3File>,
    replannedActions: List<S3SyncAction>,
    observedMissingRemotePaths: Set<String>,
): PreparedS3Sync {
    val replannedByPath = replannedActions.associateBy(S3SyncAction::path)
    val mergedActions =
        plan.actions
            .asSequence()
            .filterNot { action -> action.path in verifiedPaths }
            .plus(replannedActions.asSequence())
            .sortedBy(S3SyncAction::path)
            .toList()
    return copy(
        remoteFiles = verifiedRemoteFiles,
        plan =
            S3SyncPlan(
                actions = mergedActions,
                pendingChanges = mergedActions.count { action -> action.direction != S3SyncDirection.NONE },
            ),
        normalActions =
            mergedActions.filter { action ->
                action.direction != S3SyncDirection.CONFLICT &&
                    action != replannedByPath[action.path]?.takeIf { it.direction == S3SyncDirection.CONFLICT }
            },
        observedMissingRemotePaths = this.observedMissingRemotePaths + observedMissingRemotePaths,
    )
}
