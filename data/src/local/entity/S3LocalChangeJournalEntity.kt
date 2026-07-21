package com.lomo.data.local.entity

import kotlinx.serialization.Serializable

@Serializable
data class S3LocalChangeJournalEntity(
    val workspaceGeneration: String = TRANSIENT_WORKSPACE_GENERATION,
    val id: String,
    val kind: String,
    val filename: String,
    val changeType: String,
    val updatedAt: Long,
) {
    init {
        require(workspaceGeneration.isNotBlank()) { "S3 local journal must be scoped to a workspace generation" }
    }
}
