package com.lomo.data.repository

import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.data.sync.SyncDirectoryLayout

internal data class S3EffectiveLocalChangeSet(
    val journalEntries: Map<String, S3LocalChangeJournalEntry>,
    val currentLocalFileCount: Int? = null,
    val stalePersistedIds: Set<String> = emptySet(),
    val localAuditRan: Boolean = false,
    val nextLocalAuditCursor: String? = null,
    val completedLocalAuditCycle: Boolean = false,
)

internal suspend fun resolveEffectiveLocalChangeSet(
    journalEntries: Map<String, S3LocalChangeJournalEntry>,
    layout: SyncDirectoryLayout,
    mode: S3LocalSyncMode,
    fileBridgeScope: S3SyncFileBridgeScope,
    metadataDao: S3SyncMetadataDao,
    boundedLocalAudit: Boolean = false,
    localAuditCursor: String? = null,
): S3EffectiveLocalChangeSet {
    if (mode !is S3LocalSyncMode.VaultRoot || mode.legacyRemoteCompatibility) {
        return S3EffectiveLocalChangeSet(journalEntries = journalEntries)
    }
    if (boundedLocalAudit) {
        return resolveBoundedLocalAuditChangeSet(
            journalEntries = journalEntries,
            layout = layout,
            mode = mode,
            fileBridgeScope = fileBridgeScope,
            metadataDao = metadataDao,
            localAuditCursor = localAuditCursor,
        )
    }
    val effectiveEntries =
        journalEntries.values
            .filter { entry -> entry.relativePath(layout, mode) != null }
            .associateBy(S3LocalChangeJournalEntry::id)
    val effectiveIds = effectiveEntries.keys
    return S3EffectiveLocalChangeSet(
        journalEntries = effectiveEntries,
        stalePersistedIds = journalEntries.keys - effectiveIds,
    )
}

private suspend fun resolveBoundedLocalAuditChangeSet(
    journalEntries: Map<String, S3LocalChangeJournalEntry>,
    layout: SyncDirectoryLayout,
    mode: S3LocalSyncMode.VaultRoot,
    fileBridgeScope: S3SyncFileBridgeScope,
    metadataDao: S3SyncMetadataDao,
    localAuditCursor: String?,
): S3EffectiveLocalChangeSet {
    val persistedByPath =
        journalEntries.values
            .mapNotNull { entry -> entry.relativePath(layout, mode)?.let { path -> path to entry } }
            .toMap()
    val metadataPage =
        metadataDao
            .getLocalAuditPage(localAuditCursor, S3_LOCAL_AUDIT_PAGE_SIZE)
            .associateBy(S3SyncMetadataEntity::relativePath)
    val localFilePage =
        fileBridgeScope
            .localAuditPage(
                afterRelativePath = localAuditCursor,
                limit = S3_LOCAL_AUDIT_PAGE_SIZE,
            ).associateBy(LocalS3File::path)
    val auditedPaths =
        (metadataPage.keys + localFilePage.keys)
            .sorted()
            .take(S3_LOCAL_AUDIT_PAGE_SIZE)
    val fallbackLocalFiles =
        resolveLocalS3FilesForPaths(
            auditedPaths.filter { path -> path !in localFilePage },
        ) { path -> fileBridgeScope.localFile(path, layout) }
    val effectiveByPath = persistedByPath.toMutableMap()
    val now = System.currentTimeMillis()
    auditedPaths.forEach { path ->
        val metadata = metadataPage[path]
        val localFile = localFilePage[path] ?: fallbackLocalFiles[path]
        when {
            metadata == null && localFile != null ->
                effectiveByPath[path] =
                    persistedByPath[path]?.copy(
                        changeType = S3LocalChangeType.UPSERT,
                        updatedAt = maxOf(localFile.lastModified, now),
                    ) ?: S3LocalChangeJournalEntry.generic(
                        relativePath = path,
                        changeType = S3LocalChangeType.UPSERT,
                        updatedAt = maxOf(localFile.lastModified, now),
                    )

            metadata != null && localFile == null ->
                effectiveByPath[path] =
                    persistedByPath[path]?.copy(
                        changeType = S3LocalChangeType.DELETE,
                        updatedAt = now,
                    ) ?: S3LocalChangeJournalEntry.generic(
                        relativePath = path,
                        changeType = S3LocalChangeType.DELETE,
                        updatedAt = now,
                    )

            metadata != null &&
                localFile != null &&
                (metadata.localLastModified != localFile.lastModified || metadata.localSize != localFile.size) ->
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
    return S3EffectiveLocalChangeSet(
        journalEntries = effectiveByPath.values.associateBy(S3LocalChangeJournalEntry::id),
        localAuditRan = true,
        nextLocalAuditCursor = auditedPaths.lastOrNull()?.takeIf { auditedPaths.size == S3_LOCAL_AUDIT_PAGE_SIZE },
        completedLocalAuditCycle = auditedPaths.size < S3_LOCAL_AUDIT_PAGE_SIZE,
    )
}

private const val S3_LOCAL_AUDIT_PAGE_SIZE = 128
