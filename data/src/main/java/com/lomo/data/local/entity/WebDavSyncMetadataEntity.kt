package com.lomo.data.local.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "webdav_sync_metadata")
data class WebDavSyncMetadataEntity(
    @PrimaryKey
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
    companion object {
        const val NONE = "NONE"
        const val UNCHANGED = "UNCHANGED"
    }
}
