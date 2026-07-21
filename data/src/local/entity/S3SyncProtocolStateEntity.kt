package com.lomo.data.local.entity

import kotlinx.serialization.Serializable

@Serializable
data class S3SyncProtocolStateEntity(
    val workspaceGeneration: String = TRANSIENT_WORKSPACE_GENERATION,
    val id: Int = SINGLETON_ID,
    val protocolVersion: Int,
    val lastSuccessfulSyncAt: Long?,
    val lastFastSyncAt: Long? = null,
    val lastReconcileAt: Long? = null,
    val lastFullRemoteScanAt: Long? = null,
    val indexedLocalFileCount: Int,
    val indexedRemoteFileCount: Int,
    val localModeFingerprint: String? = null,
    val localAuditCursor: String? = null,
    val remoteScanCursor: String? = null,
    val scanEpoch: Long = 0L,
) {
    init {
        require(workspaceGeneration.isNotBlank()) { "S3 protocol state must be scoped to a workspace generation" }
    }

    companion object {
        const val SINGLETON_ID = 1
    }
}
