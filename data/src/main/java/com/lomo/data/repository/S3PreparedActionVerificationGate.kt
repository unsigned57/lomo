package com.lomo.data.repository

import com.lomo.domain.model.S3RemoteVerificationLevel
import com.lomo.domain.model.S3SyncDirection

internal class S3PreparedActionVerificationGate(
    private val planner: S3SyncPlanner,
    private val encodingSupport: S3SyncEncodingSupport,
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
        val verifiedRemoteFiles = prepared.remoteFiles.toMutableMap()
        val verifiedMissingRemotePaths = linkedSetOf<String>()
        val observedMissingRemotePaths = linkedSetOf<String>()
        val existingRemoteIndexByPath =
            if (remoteIndexStore.remoteIndexEnabled) {
                remoteIndexStore
                    .readByRelativePaths(candidatePaths)
                    .associateBy(S3RemoteIndexEntry::relativePath)
            } else {
                emptyMap()
            }
        candidatePaths.forEach { path ->
            val action = prepared.normalActions.firstOrNull { it.path == path } ?: return@forEach
            if (!shouldVerifyPath(action = action, prepared = prepared)) {
                return@forEach
            }
            val remotePath =
                prepared.remoteFiles[path]?.remotePath
                    ?: prepared.metadataByPath[path]?.remotePath
                    ?: encodingSupport.remotePathFor(path, config)
            val verifiedRemote = client.getObjectMetadata(remotePath)?.toVerifiedRemoteFile(path)
            if (verifiedRemote == null) {
                verifiedRemoteFiles.remove(path)
                observedMissingRemotePaths += path
                if (hasStableMissingEvidence(action, prepared, existingRemoteIndexByPath[path])) {
                    verifiedMissingRemotePaths += path
                }
            } else {
                verifiedRemoteFiles[path] = verifiedRemote
                verifiedMissingRemotePaths.remove(path)
                observedMissingRemotePaths.remove(path)
            }
        }
        val initialReplannedActions =
            planner.planPaths(
                paths = candidatePaths,
                localFiles = prepared.localFiles,
                remoteFiles = verifiedRemoteFiles,
                metadata = prepared.metadataByPath,
                preResolvedActionsByPath =
                    prepared.preResolvedActionsByPath.filterKeys(candidatePaths::contains),
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
                    verifiedPaths = candidatePaths,
                    verifiedRemoteFiles = verifiedRemoteFiles,
                    replannedActions = replannedActions,
                    observedMissingRemotePaths = observedMissingRemotePaths,
                ),
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
}

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
