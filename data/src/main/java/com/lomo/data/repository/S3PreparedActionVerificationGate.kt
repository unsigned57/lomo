package com.lomo.data.repository

import com.lomo.domain.model.S3RemoteVerificationLevel
import com.lomo.domain.model.S3SyncDirection
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class S3PreparedActionVerificationGate(
    private val planner: S3SyncPlanner,
    private val encodingSupport: S3SyncEncodingSupport,
    private val performanceTuner: SyncPerformanceTuner = DisabledSyncPerformanceTuner,
    private val remoteIndexStore: S3RemoteIndexStore = DisabledS3RemoteIndexStore,
) {
    suspend fun verify(
        prepared: PreparedS3Sync,
        client: com.lomo.data.s3.LomoS3Client,
        config: S3ResolvedConfig,
        layout: com.lomo.data.sync.SyncDirectoryLayout? = null,
        fileBridgeScope: S3SyncFileBridgeScope? = null,
        mode: S3LocalSyncMode? = null,
    ): VerifiedPreparedS3Sync {
        val candidatePaths =
            prepared.normalActions
                .asSequence()
                .filter(::requiresVerification)
                .map(S3SyncAction::path)
                .toSortedSet()
        if (candidatePaths.isEmpty()) {
            return VerifiedPreparedS3Sync(prepared = prepared)
        }
        val actionsByPath = prepared.normalActions.associateBy(S3SyncAction::path)
        val existingRemoteIndexByPath = loadExistingRemoteIndexByPath(candidatePaths)
        val pathSelection =
            selectPathsForVerification(
                candidatePaths = candidatePaths,
                actionsByPath = actionsByPath,
                prepared = prepared,
                existingRemoteIndexByPath = existingRemoteIndexByPath,
            )
        val verifiedMissingRemotePaths = pathSelection.verifiedMissingRemotePaths
        val pathsToVerify = pathSelection.pathsToVerify
        if (pathsToVerify.isEmpty() && verifiedMissingRemotePaths.isEmpty()) {
            return VerifiedPreparedS3Sync(prepared = prepared)
        }
        val verifiedRemoteFiles = prepared.remoteFiles.toMutableMap()
        val observedMissingRemotePaths = linkedSetOf<String>()
        loadVerificationResults(
            pathsToVerify = pathsToVerify,
            actionsByPath = actionsByPath,
            prepared = prepared,
            client = client,
            config = config,
        ).forEach { result ->
            applyVerificationResult(
                result = result,
                prepared = prepared,
                existingRemoteIndexByPath = existingRemoteIndexByPath,
                verifiedRemoteFiles = verifiedRemoteFiles,
                verifiedMissingRemotePaths = verifiedMissingRemotePaths,
                observedMissingRemotePaths = observedMissingRemotePaths,
            )
        }
        val replannedPaths = (pathsToVerify + verifiedMissingRemotePaths).toSortedSet()
        val initialReplannedActions =
            planner.planPaths(
                paths = replannedPaths,
                localFiles = prepared.localFiles,
                remoteFiles = verifiedRemoteFiles,
                metadata = prepared.metadataByPath,
                preResolvedActionsByPath =
                    prepared.preResolvedActionsByPath.filterKeys(replannedPaths::contains),
                missingRemoteVerificationByPath =
                    verifiedMissingRemotePaths.associateWith {
                        S3RemoteVerificationLevel.VERIFIED_REMOTE
                    },
                defaultMissingRemoteVerification = S3RemoteVerificationLevel.UNKNOWN_REMOTE,
            )
        val replannedActions =
            if (layout != null && fileBridgeScope != null && mode != null) {
                refineTrackedMemoPlanWithContent(
                    plan = initialReplannedActions,
                    localFiles = prepared.localFiles,
                    remoteFiles = verifiedRemoteFiles,
                    metadataByPath = prepared.metadataByPath,
                    client = client,
                    config = config,
                    encodingSupport = encodingSupport,
                    fileBridgeScope = fileBridgeScope,
                    layout = layout,
                    mode = mode,
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
            verifiedMissingRemotePaths = verifiedMissingRemotePaths,
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

    private fun selectPathsForVerification(
        candidatePaths: Set<String>,
        actionsByPath: Map<String, S3SyncAction>,
        prepared: PreparedS3Sync,
        existingRemoteIndexByPath: Map<String, S3RemoteIndexEntry>,
    ): S3VerificationPathSelection {
        val verifiedMissingRemotePaths = linkedSetOf<String>()
        val pathsToVerify =
            candidatePaths.filterTo(linkedSetOf()) { path ->
                val action = actionsByPath[path] ?: return@filterTo false
                if (!shouldVerifyPath(action = action, prepared = prepared)) {
                    return@filterTo false
                }
                if (canTrustStableMissingEvidence(action, prepared, existingRemoteIndexByPath[path])) {
                    verifiedMissingRemotePaths += path
                    return@filterTo false
                }
                true
            }
        return S3VerificationPathSelection(
            pathsToVerify = pathsToVerify,
            verifiedMissingRemotePaths = verifiedMissingRemotePaths,
        )
    }

    private fun requiresVerification(action: S3SyncAction): Boolean =
        action.direction == S3SyncDirection.UPLOAD ||
            action.direction == S3SyncDirection.DELETE_LOCAL ||
            action.direction == S3SyncDirection.DELETE_REMOTE

    private fun shouldVerifyPath(
        action: S3SyncAction,
        prepared: PreparedS3Sync,
    ): Boolean {
        val remote = prepared.remoteFiles[action.path]
        val metadata = prepared.metadataByPath[action.path]
        return when (action.direction) {
            S3SyncDirection.UPLOAD ->
                remote?.verified != true &&
                    (remote != null || metadata != null)

            S3SyncDirection.DELETE_REMOTE -> remote?.verified != true

            S3SyncDirection.DELETE_LOCAL -> remote == null && metadata != null

            S3SyncDirection.NONE,
            S3SyncDirection.DOWNLOAD,
            S3SyncDirection.CONFLICT,
            -> false
        }
    }

    private fun canTrustStableMissingEvidence(
        action: S3SyncAction,
        prepared: PreparedS3Sync,
        existingRemoteIndex: S3RemoteIndexEntry?,
    ): Boolean =
        action.direction == S3SyncDirection.DELETE_LOCAL &&
            hasStableMissingEvidence(
                action = action,
                prepared = prepared,
                existingRemoteIndex = existingRemoteIndex,
            )

    private fun hasStableMissingEvidence(
        action: S3SyncAction,
        prepared: PreparedS3Sync,
        existingRemoteIndex: S3RemoteIndexEntry?,
    ): Boolean =
        when {
            action.direction != S3SyncDirection.DELETE_LOCAL -> true
            prepared.completeSnapshot -> true
            action.path in prepared.remoteReconcileState?.missingRemotePaths.orEmpty() -> true
            existingRemoteIndex?.missingOnLastScan == true && existingRemoteIndex.lastVerifiedAt != null -> true
            else -> false
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
                    val remotePath =
                        prepared.remoteFiles[path]?.remotePath
                            ?: prepared.metadataByPath[path]?.remotePath
                            ?: encodingSupport.remotePathFor(path, config)
                    verificationLimiter.withPermit {
                        S3VerificationResult(
                            path = path,
                            action = action,
                            verifiedRemote = client.getObjectMetadata(remotePath)?.toVerifiedRemoteFile(path),
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
            if (hasStableMissingEvidence(result.action, prepared, existingRemoteIndexByPath[result.path])) {
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

private fun com.lomo.data.s3.S3RemoteObject.toVerifiedRemoteFile(path: String): RemoteS3File =
    RemoteS3File(
        path = path,
        etag = eTag,
        lastModified = lastModified,
        remotePath = key,
        verificationLevel = S3RemoteVerificationLevel.VERIFIED_REMOTE,
    )
