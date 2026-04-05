package com.lomo.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "s3_sync_protocol_state")
data class S3SyncProtocolStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "protocol_version") val protocolVersion: Int,
    @ColumnInfo(name = "last_manifest_revision") val lastManifestRevision: Long?,
    @ColumnInfo(name = "last_successful_sync_at") val lastSuccessfulSyncAt: Long?,
    @ColumnInfo(name = "indexed_local_file_count") val indexedLocalFileCount: Int,
    @ColumnInfo(name = "indexed_remote_file_count") val indexedRemoteFileCount: Int,
    @ColumnInfo(name = "local_mode_fingerprint") val localModeFingerprint: String? = null,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
