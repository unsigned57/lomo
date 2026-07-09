package com.lomo.data.local.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity

@Entity(
    tableName = "s3_remote_shard_state",
    primaryKeys = ["workspace_generation", "bucket_id"],
)
data class S3RemoteShardStateEntity(
    @ColumnInfo(name = "workspace_generation") val workspaceGeneration: String = TRANSIENT_WORKSPACE_GENERATION,
    @ColumnInfo(name = "bucket_id") val bucketId: String,
    @ColumnInfo(name = "relative_prefix") val relativePrefix: String?,
    @ColumnInfo(name = "last_scanned_at") val lastScannedAt: Long,
    @ColumnInfo(name = "last_object_count") val lastObjectCount: Int = 0,
    @ColumnInfo(name = "last_duration_ms") val lastDurationMs: Long = 0L,
    @ColumnInfo(name = "last_change_count") val lastChangeCount: Int = 0,
    @ColumnInfo(name = "idle_scan_streak") val idleScanStreak: Int = 0,
    @ColumnInfo(name = "last_verification_attempt_count") val lastVerificationAttemptCount: Int = 0,
    @ColumnInfo(name = "last_verification_failure_count") val lastVerificationFailureCount: Int = 0,
) {
    init {
        require(workspaceGeneration.isNotBlank()) { "S3 shard state must be scoped to a workspace generation" }
    }
}
