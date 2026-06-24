package com.lomo.data.local.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index

@Entity(
    tableName = "s3_remote_index",
    indices = [Index(value = ["scan_priority", "last_verified_at"])],
    primaryKeys = ["workspace_generation", "relative_path"],
)
data class S3RemoteIndexEntity(
    @ColumnInfo(name = "workspace_generation") val workspaceGeneration: String = TRANSIENT_WORKSPACE_GENERATION,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "remote_path") val remotePath: String,
    @ColumnInfo(name = "etag") val etag: String?,
    @ColumnInfo(name = "remote_last_modified") val remoteLastModified: Long?,
    @ColumnInfo(name = "size") val size: Long?,
    @ColumnInfo(name = "content_md5") val contentMd5: String? = null,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
    @ColumnInfo(name = "last_verified_at") val lastVerifiedAt: Long?,
    @ColumnInfo(name = "scan_bucket") val scanBucket: String,
    @ColumnInfo(name = "scan_priority") val scanPriority: Int = 0,
    @ColumnInfo(name = "dirty_suspect") val dirtySuspect: Boolean = false,
    @ColumnInfo(name = "missing_on_last_scan") val missingOnLastScan: Boolean = false,
    @ColumnInfo(name = "scan_epoch") val scanEpoch: Long = 0L,
) {
    init {
        require(workspaceGeneration.isNotBlank()) { "S3 remote index must be scoped to a workspace generation" }
    }
}
