package com.lomo.data.local.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity

@Entity(
    tableName = "pending_sync_conflict",
    primaryKeys = ["workspace_generation", "backend"],
)
data class PendingSyncConflictEntity(
    @ColumnInfo(name = "workspace_generation") val workspaceGeneration: String = TRANSIENT_WORKSPACE_GENERATION,
    @ColumnInfo(name = "backend") val backend: String,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
) {
    init {
        require(workspaceGeneration.isNotBlank()) { "Pending sync conflict must be scoped to a workspace generation" }
    }
}
