package com.lomo.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "s3_remote_index")
data class S3RemoteIndexEntity(
    @PrimaryKey
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "remote_path") val remotePath: String,
    @ColumnInfo(name = "etag") val etag: String?,
    @ColumnInfo(name = "remote_last_modified") val remoteLastModified: Long?,
    @ColumnInfo(name = "size") val size: Long?,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
    @ColumnInfo(name = "last_verified_at") val lastVerifiedAt: Long?,
    @ColumnInfo(name = "scan_bucket") val scanBucket: String,
    @ColumnInfo(name = "scan_priority") val scanPriority: Int = 0,
    @ColumnInfo(name = "dirty_suspect") val dirtySuspect: Boolean = false,
    @ColumnInfo(name = "missing_on_last_scan") val missingOnLastScan: Boolean = false,
    @ColumnInfo(name = "scan_epoch") val scanEpoch: Long = 0L,
)
