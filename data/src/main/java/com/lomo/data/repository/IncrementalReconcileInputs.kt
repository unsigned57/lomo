package com.lomo.data.repository

import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.SyncConflictSet

internal data class IncrementalReconcileInputs(
    val plannerMetadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
    val remoteFiles: MutableMap<String, RemoteS3File>,
    val localFiles: Map<String, LocalS3File>,
)

internal suspend fun loadIncrementalReconcileInputs(
    runtime: S3SyncRepositoryContext,
    candidatePaths: Set<String>,
    journalEntriesByPath: Map<String, S3LocalChangeJournalEntry>,
    remoteReconcileState: PreparedRemoteReconcile,
    remoteIndexStore: S3RemoteIndexStore,
    fileBridgeScope: S3SyncFileBridgeScope,
    layout: com.lomo.data.sync.SyncDirectoryLayout,
): IncrementalReconcileInputs {
    val metadataByPath =
        if (candidatePaths.isEmpty()) {
            emptyMap()
        } else {
            runtime.metadataDao.getByRelativePaths(candidatePaths.toList()).associateBy { it.relativePath }
        }
    val indexedEntriesByPath =
        if (remoteIndexStore.remoteIndexEnabled) {
            remoteIndexStore.readByRelativePaths(candidatePaths).associateBy(S3RemoteIndexEntry::relativePath)
        } else {
            emptyMap()
        }
    val plannerMetadataByPath =
        mergePlannerMetadata(
            metadataByPath = metadataByPath,
            journalEntriesByPath = journalEntriesByPath,
            indexedEntriesByPath = indexedEntriesByPath,
        )
    return IncrementalReconcileInputs(
        plannerMetadataByPath = plannerMetadataByPath,
        remoteFiles =
            buildIncrementalReconcileRemoteFiles(
                indexedEntriesByPath = indexedEntriesByPath,
                plannerMetadataByPath = plannerMetadataByPath,
                remoteReconcileState = remoteReconcileState,
                remoteIndexStore = remoteIndexStore,
            ),
        localFiles = buildIncrementalReconcileLocalFiles(candidatePaths, fileBridgeScope, layout),
    )
}

internal fun buildIncrementalReconcileRemoteFiles(
    indexedEntriesByPath: Map<String, S3RemoteIndexEntry>,
    plannerMetadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
    remoteReconcileState: PreparedRemoteReconcile,
    remoteIndexStore: S3RemoteIndexStore,
): MutableMap<String, RemoteS3File> {
    val remoteFiles =
        if (remoteIndexStore.remoteIndexEnabled) {
            indexedEntriesByPath.values
                .asSequence()
                .filterNot(S3RemoteIndexEntry::missingOnLastScan)
                .associate { entry -> entry.relativePath to entry.toCachedRemoteFile() }
                .toMutableMap()
        } else {
            plannerMetadataByPath.values
                .associate { metadata ->
                    metadata.relativePath to
                        RemoteS3File(
                            path = metadata.relativePath,
                            etag = metadata.etag,
                            lastModified = metadata.remoteLastModified,
                            remotePath = metadata.remotePath,
                            verificationLevel = S3RemoteVerificationLevel.INDEX_CACHED_REMOTE,
                        )
                }.toMutableMap()
        }
    remoteReconcileState.remoteFiles.forEach { (path, remoteFile) ->
        remoteFiles[path] = remoteFile
    }
    remoteReconcileState.missingRemotePaths.forEach(remoteFiles::remove)
    return remoteFiles
}

internal suspend fun buildIncrementalReconcileLocalFiles(
    candidatePaths: Set<String>,
    fileBridgeScope: S3SyncFileBridgeScope,
    layout: com.lomo.data.sync.SyncDirectoryLayout,
): Map<String, LocalS3File> =
    candidatePaths
        .associateWith { path -> fileBridgeScope.localFile(path, layout) }
        .mapNotNull { (path, file) -> file?.let { path to it } }
        .toMap()

internal suspend fun prepareReconcileConflictState(
    plan: S3SyncPlan,
    runtime: S3SyncRepositoryContext,
    client: com.lomo.data.s3.LomoS3Client,
    layout: com.lomo.data.sync.SyncDirectoryLayout,
    config: S3ResolvedConfig,
    remoteFiles: Map<String, RemoteS3File>,
    fileBridgeScope: S3SyncFileBridgeScope,
    mode: S3LocalSyncMode,
    encodingSupport: S3SyncEncodingSupport,
): Pair<Set<String>, SyncConflictSet?> {
    val conflictActions = plan.actions.filter { it.direction == S3SyncDirection.CONFLICT }
    val conflictPaths = conflictActions.map(S3SyncAction::path).toSet()
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
        )
    if (conflictSet != null) {
        runtime.stateHolder.state.value = S3SyncState.ConflictDetected(conflictSet)
    }
    return conflictPaths to conflictSet
}
