package com.lomo.data.local.entity

import kotlinx.serialization.Serializable

@Serializable
data class S3RemoteIndexEntity(
    val workspaceGeneration: String = TRANSIENT_WORKSPACE_GENERATION,
    val relativePath: String,
    val remotePath: String,
    val etag: String?,
    val remoteLastModified: Long?,
    val size: Long?,
    val contentMd5: String? = null,
    val lastSeenAt: Long,
    val lastVerifiedAt: Long?,
    val scanBucket: String,
    val scanPriority: Int = 0,
    val dirtySuspect: Boolean = false,
    val missingOnLastScan: Boolean = false,
    val scanEpoch: Long = 0L,
) {
    init {
        require(workspaceGeneration.isNotBlank()) { "S3 remote index must be scoped to a workspace generation" }
    }
}
