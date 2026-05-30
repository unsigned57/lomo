package com.lomo.data.local.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index

@Entity(
    tableName = "s3_local_change_journal",
    indices = [Index(value = ["updated_at"])],
    primaryKeys = ["workspace_generation", "id"],
)
data class S3LocalChangeJournalEntity(
    @ColumnInfo(name = "workspace_generation") val workspaceGeneration: String = TRANSIENT_WORKSPACE_GENERATION,
    val id: String,
    val kind: String,
    val filename: String,
    @ColumnInfo(name = "change_type") val changeType: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    init {
        require(workspaceGeneration.isNotBlank()) { "S3 local journal must be scoped to a workspace generation" }
    }
}
