package com.lomo.data.local.entity

import kotlinx.serialization.Serializable

@Serializable
data class S3SyncMetadataEntity(
    val workspaceGeneration: String = TRANSIENT_WORKSPACE_GENERATION,
    val relativePath: String,
    val remotePath: String,
    val etag: String?,
    val remoteLastModified: Long?,
    val localLastModified: Long?,
    val localSize: Long? = null,
    val remoteSize: Long? = null,
    val localFingerprint: String? = null,
    val lastSyncedAt: Long,
    val lastResolvedDirection: String,
    val lastResolvedReason: String,
) {
    init {
        require(workspaceGeneration.isNotBlank()) { "S3 sync metadata must be scoped to a workspace generation" }
    }

    companion object {
        const val NONE = "NONE"
        const val UNCHANGED = "UNCHANGED"
    }
}
