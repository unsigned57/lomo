package com.lomo.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "s3_remote_shard_state")
data class S3RemoteShardStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "bucket_id") val bucketId: String,
    @ColumnInfo(name = "relative_prefix") val relativePrefix: String?,
    @ColumnInfo(name = "last_scanned_at") val lastScannedAt: Long,
    @ColumnInfo(name = "last_object_count") val lastObjectCount: Int = 0,
    @ColumnInfo(name = "last_duration_ms") val lastDurationMs: Long = 0L,
    @ColumnInfo(name = "last_change_count") val lastChangeCount: Int = 0,
    @ColumnInfo(name = "idle_scan_streak") val idleScanStreak: Int = 0,
    @ColumnInfo(name = "last_verification_attempt_count") val lastVerificationAttemptCount: Int = 0,
    @ColumnInfo(name = "last_verification_failure_count") val lastVerificationFailureCount: Int = 0,
)
