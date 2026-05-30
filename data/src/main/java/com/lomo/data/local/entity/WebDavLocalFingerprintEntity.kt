package com.lomo.data.local.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity

@Entity(
    tableName = "webdav_local_fingerprint",
    primaryKeys = ["workspace_generation", "path"],
)
data class WebDavLocalFingerprintEntity(
    @ColumnInfo(name = "workspace_generation") val workspaceGeneration: String = TRANSIENT_WORKSPACE_GENERATION,
    val path: String,
    @ColumnInfo(name = "last_modified") val lastModified: Long,
    val size: Long? = null,
    val fingerprint: String,
) {
    init {
        require(workspaceGeneration.isNotBlank()) {
            "WebDAV local fingerprint must be scoped to a workspace generation"
        }
    }
}
