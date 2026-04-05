package com.lomo.data.repository

import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.dao.S3SyncRemoteMetadataSnapshot
import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.data.sync.SyncDirectoryLayout

internal data class S3IncrementalPreparation(
    val manifest: S3RemoteManifest,
    val remoteFiles: Map<String, RemoteS3File>,
    val localFiles: Map<String, LocalS3File>,
    val metadataByPath: Map<String, S3SyncMetadataEntity>,
    val journalEntriesByPath: Map<String, S3LocalChangeJournalEntry>,
    val remoteChangedPaths: Set<String>,
    val plan: S3SyncPlan,
)

internal data class S3LocalOnlyIncrementalPreparation(
    val localFiles: Map<String, LocalS3File>,
    val remoteFiles: Map<String, RemoteS3File>,
    val metadataByPath: Map<String, S3SyncMetadataEntity>,
    val journalEntriesByPath: Map<String, S3LocalChangeJournalEntry>,
    val plan: S3SyncPlan,
)

internal data class S3IncrementalMetadataPreparation(
    val metadataByPath: Map<String, S3SyncMetadataEntity>,
    val remoteChangedPaths: Set<String>,
)

internal fun S3RemoteManifest.remoteFilesByPath(): Map<String, RemoteS3File> =
    entries.associate { entry ->
        entry.relativePath to
            RemoteS3File(
                path = entry.relativePath,
                etag = entry.etag,
                lastModified = entry.remoteLastModified,
                remotePath = entry.remotePath,
            )
    }

internal fun Map<String, S3LocalChangeJournalEntry>.resolvePaths(
    layout: SyncDirectoryLayout,
    mode: S3LocalSyncMode,
): Map<String, S3LocalChangeJournalEntry> =
    values
        .mapNotNull { entry -> entry.relativePath(layout, mode)?.let { path -> path to entry } }
        .toMap()

internal fun S3RemoteManifest.changedPathsAgainst(
    metadataByPath: Map<String, S3SyncMetadataEntity>,
): Set<String> {
    val manifestEntries = entries.associateBy(S3RemoteManifestEntry::relativePath)
    return buildSet {
        metadataByPath.keys.forEach { path ->
            val entry = manifestEntries[path]
            if (entry == null || !metadataByPath.getValue(path).matches(entry)) {
                add(path)
            }
        }
        manifestEntries.keys.forEach { path ->
            if (!metadataByPath.get(path).matches(manifestEntries.getValue(path))) {
                add(path)
            }
        }
    }
}

internal fun S3RemoteManifest.changedPathsAgainstRemoteSnapshots(
    metadataByPath: Map<String, S3SyncRemoteMetadataSnapshot>,
): Set<String> {
    val manifestEntries = entries.associateBy(S3RemoteManifestEntry::relativePath)
    return buildSet {
        metadataByPath.keys.forEach { path ->
            val entry = manifestEntries[path]
            if (entry == null || !metadataByPath.getValue(path).matches(entry)) {
                add(path)
            }
        }
        manifestEntries.keys.forEach { path ->
            if (!metadataByPath.get(path).matches(manifestEntries.getValue(path))) {
                add(path)
            }
        }
    }
}

internal suspend fun prepareIncrementalSync(
    manifest: S3RemoteManifest,
    journalEntries: Map<String, S3LocalChangeJournalEntry>,
    layout: SyncDirectoryLayout,
    mode: S3LocalSyncMode,
    fileBridgeScope: S3SyncFileBridgeScope,
    planner: S3SyncPlanner,
    metadataDao: S3SyncMetadataDao,
    protocolState: S3SyncProtocolState,
): S3IncrementalPreparation {
    val remoteFiles = manifest.remoteFilesByPath()
    val journalEntriesByPath = journalEntries.resolvePaths(layout, mode)
    val metadataPreparation =
        prepareIncrementalMetadata(
            manifest = manifest,
            protocolState = protocolState,
            journalEntriesByPath = journalEntriesByPath,
            metadataDao = metadataDao,
        )
    val metadataByPath = metadataPreparation.metadataByPath
    val remoteChangedPaths = metadataPreparation.remoteChangedPaths
    val candidatePaths = (journalEntriesByPath.keys + remoteChangedPaths).toSortedSet()
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
        )
    return S3IncrementalPreparation(
        manifest = manifest,
        remoteFiles = remoteFiles,
        localFiles = localFiles,
        metadataByPath = metadataByPath,
        journalEntriesByPath = journalEntriesByPath,
        remoteChangedPaths = remoteChangedPaths,
        plan = plan,
    )
}

internal suspend fun prepareLocalOnlyIncrementalSync(
    journalEntries: Map<String, S3LocalChangeJournalEntry>,
    layout: SyncDirectoryLayout,
    mode: S3LocalSyncMode,
    fileBridgeScope: S3SyncFileBridgeScope,
    planner: S3SyncPlanner,
    metadataDao: S3SyncMetadataDao,
): S3LocalOnlyIncrementalPreparation {
    val journalEntriesByPath = journalEntries.resolvePaths(layout, mode)
    val candidatePaths = journalEntriesByPath.keys.toSortedSet()
    val metadataByPath =
        if (candidatePaths.isEmpty()) {
            emptyMap()
        } else {
            metadataDao.getByRelativePaths(candidatePaths.toList()).associateBy(S3SyncMetadataEntity::relativePath)
        }
    val remoteFiles =
        metadataByPath.values.associate { metadata ->
            metadata.relativePath to
                RemoteS3File(
                    path = metadata.relativePath,
                    etag = metadata.etag,
                    lastModified = metadata.remoteLastModified,
                    remotePath = metadata.remotePath,
                )
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
        )
    return S3LocalOnlyIncrementalPreparation(
        localFiles = localFiles,
        remoteFiles = remoteFiles,
        metadataByPath = metadataByPath,
        journalEntriesByPath = journalEntriesByPath,
        plan = plan,
    )
}

internal suspend fun prepareIncrementalMetadata(
    manifest: S3RemoteManifest,
    protocolState: S3SyncProtocolState,
    journalEntriesByPath: Map<String, S3LocalChangeJournalEntry>,
    metadataDao: S3SyncMetadataDao,
): S3IncrementalMetadataPreparation {
    if (protocolState.lastManifestRevision == manifest.revision) {
        val journalPaths = journalEntriesByPath.keys.toList()
        val metadataByPath =
            if (journalPaths.isEmpty()) {
                emptyMap()
            } else {
                metadataDao.getByRelativePaths(journalPaths).associateBy(S3SyncMetadataEntity::relativePath)
            }
        return S3IncrementalMetadataPreparation(
            metadataByPath = metadataByPath,
            remoteChangedPaths = emptySet(),
        )
    }
    val remoteMetadataByPath =
        metadataDao
            .getAllRemoteMetadataSnapshots()
            .associateBy(S3SyncRemoteMetadataSnapshot::relativePath)
    val remoteChangedPaths = manifest.changedPathsAgainstRemoteSnapshots(remoteMetadataByPath)
    val candidatePaths = (journalEntriesByPath.keys + remoteChangedPaths).toSortedSet()
    val metadataByPath =
        if (candidatePaths.isEmpty()) {
            emptyMap()
        } else {
            metadataDao.getByRelativePaths(candidatePaths.toList()).associateBy(S3SyncMetadataEntity::relativePath)
        }
    return S3IncrementalMetadataPreparation(
        metadataByPath = metadataByPath,
        remoteChangedPaths = remoteChangedPaths,
    )
}

private fun S3SyncMetadataEntity?.matches(entry: S3RemoteManifestEntry): Boolean =
    this != null &&
        remotePath == entry.remotePath &&
        etag == entry.etag &&
        remoteLastModified == entry.remoteLastModified

private fun S3SyncRemoteMetadataSnapshot?.matches(entry: S3RemoteManifestEntry): Boolean =
    this != null &&
        remotePath == entry.remotePath &&
        etag == entry.etag &&
        remoteLastModified == entry.remoteLastModified
