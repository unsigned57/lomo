package com.lomo.data.local.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity

@Entity(
    tableName = "s3_sync_protocol_state",
    primaryKeys = ["workspace_generation", "id"],
)
data class S3SyncProtocolStateEntity(
    @ColumnInfo(name = "workspace_generation") val workspaceGeneration: String = TRANSIENT_WORKSPACE_GENERATION,
    val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "protocol_version") val protocolVersion: Int,
    @ColumnInfo(name = "last_successful_sync_at") val lastSuccessfulSyncAt: Long?,
    @ColumnInfo(name = "last_fast_sync_at") val lastFastSyncAt: Long? = null,
    @ColumnInfo(name = "last_reconcile_at") val lastReconcileAt: Long? = null,
    @ColumnInfo(name = "last_full_remote_scan_at") val lastFullRemoteScanAt: Long? = null,
    @ColumnInfo(name = "indexed_local_file_count") val indexedLocalFileCount: Int,
    @ColumnInfo(name = "indexed_remote_file_count") val indexedRemoteFileCount: Int,
    @ColumnInfo(name = "local_mode_fingerprint") val localModeFingerprint: String? = null,
    @ColumnInfo(name = "remote_scan_cursor") val remoteScanCursor: String? = null,
    @ColumnInfo(name = "scan_epoch") val scanEpoch: Long = 0L,
) {
    init {
        require(workspaceGeneration.isNotBlank()) { "S3 protocol state must be scoped to a workspace generation" }
    }

    companion object {
        const val SINGLETON_ID = 1
    }
}
