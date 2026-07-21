package com.lomo.data.local.entity

import kotlinx.serialization.Serializable

@Serializable
data class WebDavLocalFingerprintEntity(
    val workspaceGeneration: String = TRANSIENT_WORKSPACE_GENERATION,
    val path: String,
    val lastModified: Long,
    val size: Long? = null,
    val fingerprint: String,
) {
    init {
        require(workspaceGeneration.isNotBlank()) {
            "WebDAV local fingerprint must be scoped to a workspace generation"
        }
    }
}
