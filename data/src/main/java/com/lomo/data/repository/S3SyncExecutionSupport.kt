package com.lomo.data.repository

import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncReason
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.SyncConflictSessionKind
import com.lomo.domain.model.SyncConflictSet

internal data class PreparedS3Sync(
    val layout: com.lomo.data.sync.SyncDirectoryLayout,
    val localFiles: Map<String, LocalS3File>,
    val remoteFiles: Map<String, RemoteS3File>,
    val metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
    val plan: S3SyncPlan,
    val normalActions: List<S3SyncAction>,
    val conflictSet: SyncConflictSet?,
    val completeSnapshot: Boolean,
    val protocolState: S3SyncProtocolState?,
    val remoteFileCountHint: Int?,
    val journalEntriesById: Map<String, S3LocalChangeJournalEntry> = emptyMap(),
    val journalPathsById: Map<String, String> = emptyMap(),
    val clearableJournalIds: Set<String> = emptySet(),
    val localModeFingerprint: String? = null,
    val remoteReconcileState: PreparedRemoteReconcile? = null,
)

internal data class S3ActionExecutionResult(
    val actionOutcomes: Map<String, Pair<S3SyncDirection, S3SyncReason>>,
    val failedPaths: List<String>,
    val unresolvedPaths: Set<String>,
    val localChanged: Boolean,
    val localFilesAfterSync: Map<String, LocalS3File>,
    val remoteFilesAfterSync: Map<String, RemoteS3File>,
    val memoRefreshPlan: S3MemoRefreshPlan,
)

internal data class IndexedS3ActionExecutionResult(
    val index: Int,
    val action: S3SyncAction,
    val state: S3ActionExecutionState,
)

internal fun buildLocalOnlyPreparedSync(
    layout: com.lomo.data.sync.SyncDirectoryLayout,
    mode: S3LocalSyncMode,
    protocolState: S3SyncProtocolState,
    journalEntries: Map<String, S3LocalChangeJournalEntry>,
    localOnlyIncremental: S3IncrementalPreparation,
    conflictSet: SyncConflictSet?,
    conflictPaths: Set<String>,
): PreparedS3Sync =
    PreparedS3Sync(
        layout = layout,
        localFiles = localOnlyIncremental.localFiles,
        remoteFiles = localOnlyIncremental.remoteFiles,
        metadataByPath = localOnlyIncremental.metadataByPath,
        plan = localOnlyIncremental.plan,
        normalActions =
            localOnlyIncremental.plan.actions.filter { it.direction != S3SyncDirection.CONFLICT },
        conflictSet = conflictSet,
        completeSnapshot = false,
        protocolState = protocolState,
        remoteFileCountHint = protocolState.indexedRemoteFileCount,
        journalEntriesById = journalEntries,
        journalPathsById =
            localOnlyIncremental.journalEntriesByPath.entries.associate { (path, entry) ->
                entry.id to path
            },
        clearableJournalIds =
            localOnlyIncremental.journalEntriesByPath
                .filterKeys { path -> path !in conflictPaths }
                .values
                .map(S3LocalChangeJournalEntry::id)
                .toSet(),
        localModeFingerprint = mode.fingerprint(),
    )

internal fun canUseIncrementalSync(
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

internal fun shouldPerformFullLocalAudit(
    mode: S3LocalSyncMode,
    protocolState: S3SyncProtocolState,
): Boolean =
    mode is S3LocalSyncMode.VaultRoot &&
        (
            protocolState.lastSuccessfulSyncAt == null ||
                System.currentTimeMillis() - protocolState.lastSuccessfulSyncAt > S3_VAULT_ROOT_AUDIT_INTERVAL_MS
        )

internal fun S3SyncResult.shouldFinalizeAfterSync(): Boolean =
    this is S3SyncResult.Success ||
        (this is S3SyncResult.Error && outcomes.isNotEmpty())

internal fun computeIndexedCounts(
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

internal fun String?.compatibleWith(mode: S3LocalSyncMode): Boolean =
    this == null || this == mode.fingerprint()

internal fun S3SyncResult.stateAfterRefresh(timestamp: Long): S3SyncState =
    when (this) {
        is S3SyncResult.Success -> S3SyncState.Success(timestamp, message)
        is S3SyncResult.Error -> S3SyncState.Error(message, timestamp)
        is S3SyncResult.Conflict -> conflicts.toS3ConflictState()
        S3SyncResult.NotConfigured -> S3SyncState.NotConfigured
    }

internal fun S3SyncResult.outcomesForRefreshFailure() =
    when (this) {
        is S3SyncResult.Success -> outcomes
        is S3SyncResult.Error -> outcomes
        is S3SyncResult.Conflict -> emptyList()
        S3SyncResult.NotConfigured -> emptyList()
    }

internal fun S3MemoRefreshPlan.merge(other: S3MemoRefreshPlan): S3MemoRefreshPlan =
    when {
        this == S3MemoRefreshPlan.Full || other == S3MemoRefreshPlan.Full -> S3MemoRefreshPlan.Full
        this is S3MemoRefreshPlan.Targets && other is S3MemoRefreshPlan.Targets ->
            S3MemoRefreshPlan.Targets(this.filenames + other.filenames)
        this is S3MemoRefreshPlan.Targets -> this
        other is S3MemoRefreshPlan.Targets -> other
        else -> S3MemoRefreshPlan.None
    }

internal fun determineS3ConflictSessionKind(
    conflictActions: List<S3SyncAction>,
    metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
): SyncConflictSessionKind {
    if (conflictActions.isEmpty()) {
        return SyncConflictSessionKind.STANDARD_CONFLICT
    }
    return if (conflictActions.all { action -> action.path !in metadataByPath }) {
        SyncConflictSessionKind.INITIAL_SYNC_PREVIEW
    } else {
        SyncConflictSessionKind.STANDARD_CONFLICT
    }
}

internal fun SyncConflictSet.toS3ConflictState(): S3SyncState =
    when (sessionKind) {
        SyncConflictSessionKind.INITIAL_SYNC_PREVIEW -> S3SyncState.PreviewingInitialSync(this)
        SyncConflictSessionKind.STANDARD_CONFLICT -> S3SyncState.ConflictDetected(this)
    }
