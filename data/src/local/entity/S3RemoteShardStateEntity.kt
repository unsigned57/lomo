package com.lomo.data.local.entity

import kotlinx.serialization.Serializable

@Serializable
data class S3RemoteShardStateEntity(
    val workspaceGeneration: String = TRANSIENT_WORKSPACE_GENERATION,
    val bucketId: String,
    val relativePrefix: String?,
    val lastScannedAt: Long,
    val lastObjectCount: Int = 0,
    val lastDurationMs: Long = 0L,
    val lastChangeCount: Int = 0,
    val idleScanStreak: Int = 0,
    val lastVerificationAttemptCount: Int = 0,
    val lastVerificationFailureCount: Int = 0,
) {
    init {
        require(workspaceGeneration.isNotBlank()) { "S3 shard state must be scoped to a workspace generation" }
    }
}
