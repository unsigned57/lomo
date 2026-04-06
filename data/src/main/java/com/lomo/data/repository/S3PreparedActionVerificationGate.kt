package com.lomo.data.repository

import com.lomo.domain.model.S3RemoteVerificationLevel
import com.lomo.domain.model.S3SyncDirection

internal class S3PreparedActionVerificationGate(
    private val planner: S3SyncPlanner,
    private val encodingSupport: S3SyncEncodingSupport,
) {
    suspend fun verify(
        prepared: PreparedS3Sync,
        client: com.lomo.data.s3.LomoS3Client,
        config: S3ResolvedConfig,
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
                verifiedMissingRemotePaths += path
            } else {
                verifiedRemoteFiles[path] = verifiedRemote
                verifiedMissingRemotePaths.remove(path)
            }
        }
        val replannedActions =
            planner.planPaths(
                paths = candidatePaths,
                localFiles = prepared.localFiles,
                remoteFiles = verifiedRemoteFiles,
                metadata = prepared.metadataByPath,
                missingRemoteVerificationByPath =
                    verifiedMissingRemotePaths.associateWith {
                        S3RemoteVerificationLevel.VERIFIED_REMOTE
                    },
                defaultMissingRemoteVerification = S3RemoteVerificationLevel.UNKNOWN_REMOTE,
            ).actions
        return VerifiedPreparedS3Sync(
            prepared = prepared.rebuildAfterVerification(candidatePaths, verifiedRemoteFiles, replannedActions),
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
}

private fun PreparedS3Sync.rebuildAfterVerification(
    verifiedPaths: Set<String>,
    verifiedRemoteFiles: Map<String, RemoteS3File>,
    replannedActions: List<S3SyncAction>,
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
