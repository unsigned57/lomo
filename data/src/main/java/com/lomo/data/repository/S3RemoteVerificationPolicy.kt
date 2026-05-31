package com.lomo.data.repository

import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncReason

internal object S3RemoteVerificationPolicy {
    fun requiresPreparedVerification(action: S3SyncAction): Boolean =
        action.direction == S3SyncDirection.UPLOAD ||
            action.direction == S3SyncDirection.DELETE_LOCAL ||
            action.direction == S3SyncDirection.DELETE_REMOTE

    fun shouldHeadPreparedPath(
        action: S3SyncAction,
        prepared: PreparedS3Sync,
        remoteIndexEntry: S3RemoteIndexEntry?,
        config: S3ResolvedConfig,
    ): Boolean {
        if (!candidateNeedsPreparedVerification(action = action, prepared = prepared)) {
            return false
        }
        val stableMissingEvidence =
            action.direction == S3SyncDirection.DELETE_LOCAL &&
                canTrustStableMissingEvidence(
                    action = action,
                    completeSnapshot = prepared.completeSnapshot,
                    remoteReconcileState = prepared.remoteReconcileState,
                    remoteIndexEntry = remoteIndexEntry,
                )
        return !stableMissingEvidence &&
            !canTrustFreshRemoteIndex(
                action = action,
                protocolState = prepared.protocolState,
                remoteIndexEntry = remoteIndexEntry,
                config = config,
            )
    }

    fun shouldHeadLocalOnlyIncrementalPath(
        action: S3SyncAction?,
        path: String,
        initial: S3IncrementalPreparation,
        protocolState: S3SyncProtocolState,
        remoteIndexEntry: S3RemoteIndexEntry?,
        config: S3ResolvedConfig,
    ): Boolean {
        if (
            action == null &&
            !candidateNeedsMetadataOnlyIncrementalVerification(path = path, initial = initial)
        ) {
            return false
        }
        if (
            action != null &&
            !candidateNeedsLocalOnlyIncrementalVerification(action = action, initial = initial)
        ) {
            return false
        }
        val verificationAction = action ?: S3SyncAction(path, S3SyncDirection.UPLOAD, S3SyncReason.UNCHANGED)
        return !canTrustFreshRemoteIndex(
            action = verificationAction,
            protocolState = protocolState,
            remoteIndexEntry = remoteIndexEntry,
            config = config,
        )
    }

    fun stableMissingEvidenceVerified(
        action: S3SyncAction,
        prepared: PreparedS3Sync,
        remoteIndexEntry: S3RemoteIndexEntry?,
    ): Boolean =
        action.direction == S3SyncDirection.DELETE_LOCAL &&
            canTrustStableMissingEvidence(
                action = action,
                completeSnapshot = prepared.completeSnapshot,
                remoteReconcileState = prepared.remoteReconcileState,
                remoteIndexEntry = remoteIndexEntry,
            )

    fun canTrustFreshRemoteIndex(
        action: S3SyncAction,
        protocolState: S3SyncProtocolState?,
        remoteIndexEntry: S3RemoteIndexEntry?,
        config: S3ResolvedConfig,
    ): Boolean {
        val state = protocolState ?: return false
        val entry = remoteIndexEntry ?: return false
        if (action.direction != S3SyncDirection.UPLOAD && action.direction != S3SyncDirection.DELETE_REMOTE) {
            return false
        }
        return state.hasFreshRemoteIndex(config) &&
            entry.scanEpoch == state.scanEpoch &&
            !entry.missingOnLastScan
    }

    private fun candidateNeedsPreparedVerification(
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

    private fun candidateNeedsLocalOnlyIncrementalVerification(
        action: S3SyncAction,
        initial: S3IncrementalPreparation,
    ): Boolean {
        val remote = initial.remoteFiles[action.path]
        val metadata = initial.metadataByPath[action.path]
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

    private fun candidateNeedsMetadataOnlyIncrementalVerification(
        path: String,
        initial: S3IncrementalPreparation,
    ): Boolean =
        path in initial.metadataByPath &&
            path !in initial.localFiles &&
            path !in initial.remoteFiles

    private fun canTrustStableMissingEvidence(
        action: S3SyncAction,
        completeSnapshot: Boolean,
        remoteReconcileState: PreparedRemoteReconcile?,
        remoteIndexEntry: S3RemoteIndexEntry?,
    ): Boolean =
        when {
            action.direction != S3SyncDirection.DELETE_LOCAL -> true
            completeSnapshot -> true
            action.path in remoteReconcileState?.missingRemotePaths.orEmpty() -> true
            remoteIndexEntry?.missingOnLastScan == true && remoteIndexEntry.lastVerifiedAt != null -> true
            else -> false
        }
}
