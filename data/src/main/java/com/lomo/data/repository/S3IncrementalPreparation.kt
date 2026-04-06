package com.lomo.data.repository

import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.domain.model.S3RemoteVerificationLevel
import com.lomo.data.sync.SyncDirectoryLayout

internal data class S3IncrementalPreparation(
    val localFiles: Map<String, LocalS3File>,
    val remoteFiles: Map<String, RemoteS3File>,
    val metadataByPath: Map<String, S3SyncMetadataEntity>,
    val journalEntriesByPath: Map<String, S3LocalChangeJournalEntry>,
    val plan: S3SyncPlan,
)

internal fun Map<String, S3LocalChangeJournalEntry>.resolvePaths(
    layout: SyncDirectoryLayout,
    mode: S3LocalSyncMode,
): Map<String, S3LocalChangeJournalEntry> =
    values
        .mapNotNull { entry -> entry.relativePath(layout, mode)?.let { path -> path to entry } }
        .toMap()

internal suspend fun prepareLocalOnlyIncrementalSync(
    journalEntries: Map<String, S3LocalChangeJournalEntry>,
    layout: SyncDirectoryLayout,
    mode: S3LocalSyncMode,
    fileBridgeScope: S3SyncFileBridgeScope,
    planner: S3SyncPlanner,
    metadataDao: S3SyncMetadataDao,
    remoteIndexStore: S3RemoteIndexStore,
): S3IncrementalPreparation {
    val journalEntriesByPath = journalEntries.resolvePaths(layout, mode)
    val candidatePaths = journalEntriesByPath.keys.toSortedSet()
    val persistedMetadataByPath =
        if (candidatePaths.isEmpty()) {
            emptyMap()
        } else {
            metadataDao.getByRelativePaths(candidatePaths.toList()).associateBy(S3SyncMetadataEntity::relativePath)
        }
    val indexedEntriesByPath =
        if (remoteIndexStore.remoteIndexEnabled) {
            remoteIndexStore.readByRelativePaths(candidatePaths).associateBy(S3RemoteIndexEntry::relativePath)
        } else {
            emptyMap()
        }
    val metadataByPath =
        mergePlannerMetadata(
            metadataByPath = persistedMetadataByPath,
            journalEntriesByPath = journalEntriesByPath,
            indexedEntriesByPath = indexedEntriesByPath,
        )
    val remoteFiles =
        if (remoteIndexStore.remoteIndexEnabled) {
            indexedEntriesByPath.values
                .asSequence()
                .filterNot(S3RemoteIndexEntry::missingOnLastScan)
                .associate { entry -> entry.relativePath to entry.toCachedRemoteFile() }
        } else {
            metadataByPath.values.associate { metadata ->
                metadata.relativePath to
                    RemoteS3File(
                        path = metadata.relativePath,
                        etag = metadata.etag,
                        lastModified = metadata.remoteLastModified,
                        remotePath = metadata.remotePath,
                        verificationLevel = S3RemoteVerificationLevel.INDEX_CACHED_REMOTE,
                    )
            }
        }
    val localFiles =
        candidatePaths
            .associateWith { path -> fileBridgeScope.localFile(path, layout) }
            .mapNotNull { (path, file) -> file?.let { path to it } }
            .toMap()
    val plan =
        planner.planPaths(
            paths = candidatePaths,
            localFiles = localFiles,
            remoteFiles = remoteFiles,
            metadata = metadataByPath,
            defaultMissingRemoteVerification = S3RemoteVerificationLevel.UNKNOWN_REMOTE,
        )
    return S3IncrementalPreparation(
        localFiles = localFiles,
        remoteFiles = remoteFiles,
        metadataByPath = metadataByPath,
        journalEntriesByPath = journalEntriesByPath,
        plan = plan,
    )
}

internal fun mergePlannerMetadata(
    metadataByPath: Map<String, S3SyncMetadataEntity>,
    journalEntriesByPath: Map<String, S3LocalChangeJournalEntry>,
    indexedEntriesByPath: Map<String, S3RemoteIndexEntry>,
): Map<String, S3SyncMetadataEntity> {
    if (journalEntriesByPath.isEmpty() || indexedEntriesByPath.isEmpty()) {
        return metadataByPath
    }
    val syntheticDeletes =
        journalEntriesByPath.mapNotNull { (path, journalEntry) ->
            if (path in metadataByPath || journalEntry.changeType != S3LocalChangeType.DELETE) {
                return@mapNotNull null
            }
            val indexedEntry =
                indexedEntriesByPath[path]
                    ?.takeUnless(S3RemoteIndexEntry::missingOnLastScan)
                    ?: return@mapNotNull null
            path to syntheticDeleteMetadata(path, indexedEntry, journalEntry.updatedAt)
        }
    if (syntheticDeletes.isEmpty()) {
        return metadataByPath
    }
    return metadataByPath + syntheticDeletes
}

private fun syntheticDeleteMetadata(
    relativePath: String,
    indexedEntry: S3RemoteIndexEntry,
    deletedAt: Long,
): S3SyncMetadataEntity =
    S3SyncMetadataEntity(
        relativePath = relativePath,
        remotePath = indexedEntry.remotePath,
        etag = indexedEntry.etag,
        remoteLastModified = indexedEntry.remoteLastModified,
        localLastModified = deletedAt,
        lastSyncedAt = deletedAt,
        lastResolvedDirection = S3SyncMetadataEntity.NONE,
        lastResolvedReason = S3SyncMetadataEntity.UNCHANGED,
    )
