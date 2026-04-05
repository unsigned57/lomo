package com.lomo.data.repository

import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.sync.SyncDirectoryLayout

internal data class S3EffectiveLocalChangeSet(
    val journalEntries: Map<String, S3LocalChangeJournalEntry>,
    val currentLocalFileCount: Int? = null,
    val stalePersistedIds: Set<String> = emptySet(),
)

internal suspend fun resolveEffectiveLocalChangeSet(
    journalEntries: Map<String, S3LocalChangeJournalEntry>,
    layout: SyncDirectoryLayout,
    mode: S3LocalSyncMode,
    fileBridgeScope: S3SyncFileBridgeScope,
    metadataDao: S3SyncMetadataDao,
): S3EffectiveLocalChangeSet {
    if (mode !is S3LocalSyncMode.FileVaultRoot || mode.legacyRemoteCompatibility) {
        return S3EffectiveLocalChangeSet(journalEntries = journalEntries)
    }
    val localFiles = fileBridgeScope.localFiles(layout)
    val localMetadataByPath =
        if (localFiles.isEmpty()) {
            emptyMap()
        } else {
            metadataDao.getByRelativePaths(localFiles.keys.toList()).associateBy { it.relativePath }
        }
    val knownPaths = metadataDao.getAllRemoteMetadataSnapshots().map { it.relativePath }.toSet()
    val persistedByPath =
        journalEntries.values
            .mapNotNull { entry -> entry.relativePath(layout, mode)?.let { path -> path to entry } }
            .toMap()
    val effectiveByPath = linkedMapOf<String, S3LocalChangeJournalEntry>()
    val stalePersistedIds = journalEntries.keys.toMutableSet()
    val now = System.currentTimeMillis()

    localFiles.forEach { (path, localFile) ->
        val metadata = localMetadataByPath[path]
        if (metadata == null || metadata.localLastModified != localFile.lastModified) {
            effectiveByPath[path] =
                persistedByPath[path]?.copy(
                    changeType = S3LocalChangeType.UPSERT,
                    updatedAt = maxOf(localFile.lastModified, now),
                ) ?: S3LocalChangeJournalEntry.generic(
                    relativePath = path,
                    changeType = S3LocalChangeType.UPSERT,
                    updatedAt = maxOf(localFile.lastModified, now),
                )
        }
    }

    knownPaths.forEach { path ->
        if (path !in localFiles) {
            effectiveByPath[path] =
                persistedByPath[path]?.copy(
                    changeType = S3LocalChangeType.DELETE,
                    updatedAt = now,
                ) ?: S3LocalChangeJournalEntry.generic(
                    relativePath = path,
                    changeType = S3LocalChangeType.DELETE,
                    updatedAt = now,
                )
        }
    }

    effectiveByPath.values.forEach { entry ->
        stalePersistedIds.remove(entry.id)
    }

    return S3EffectiveLocalChangeSet(
        journalEntries = effectiveByPath.values.associateBy(S3LocalChangeJournalEntry::id),
        currentLocalFileCount = localFiles.size,
        stalePersistedIds = stalePersistedIds,
    )
}
