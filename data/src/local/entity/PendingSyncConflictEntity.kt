package com.lomo.data.local.entity

import kotlinx.serialization.Serializable

@Serializable
data class PendingSyncConflictEntity(
    val workspaceGeneration: String = TRANSIENT_WORKSPACE_GENERATION,
    val backend: String,
    val timestamp: Long,
    val payloadJson: String,
) {
    init {
        require(workspaceGeneration.isNotBlank()) { "Pending sync conflict must be scoped to a workspace generation" }
    }
}
