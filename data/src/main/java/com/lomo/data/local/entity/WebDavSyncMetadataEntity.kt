package com.lomo.data.local.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity

@Entity(
    tableName = "webdav_sync_metadata",
    primaryKeys = ["workspace_generation", "relative_path"],
)
data class WebDavSyncMetadataEntity(
    @ColumnInfo(name = "workspace_generation") val workspaceGeneration: String = TRANSIENT_WORKSPACE_GENERATION,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "remote_path") val remotePath: String,
    @ColumnInfo(name = "etag") val etag: String?,
    @ColumnInfo(name = "remote_last_modified") val remoteLastModified: Long?,
    @ColumnInfo(name = "local_last_modified") val localLastModified: Long?,
    @ColumnInfo(name = "local_fingerprint") val localFingerprint: String? = null,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long,
    @ColumnInfo(name = "last_resolved_direction") val lastResolvedDirection: String,
    @ColumnInfo(name = "last_resolved_reason") val lastResolvedReason: String,
) {
    init {
        require(workspaceGeneration.isNotBlank()) { "WebDAV sync metadata must be scoped to a workspace generation" }
    }

    companion object {
        const val NONE = "NONE"
        const val UNCHANGED = "UNCHANGED"
    }
}
