package com.lomo.data.repository

import java.security.MessageDigest

/**
 * Shared side metadata for pending Sync Inbox review (and historical conflict descriptors).
 */
data class PendingSyncSideMetadata(
    val locator: String = "",
    val contentHash: String? = null,
    val lastModified: Long? = null,
    val size: Long? = null,
    val etag: String? = null,
)

enum class PendingSyncValidationStatus {
    PENDING_RELOAD,
    VALIDATED,
    INVALIDATED,
}

data class PendingSyncConflictDescriptor(
    val source: com.lomo.domain.model.SyncBackendType,
    val workspaceGeneration: String,
    val files: List<PendingSyncConflictFileDescriptor>,
    val timestamp: Long,
    val validationStatus: PendingSyncValidationStatus,
)

data class PendingSyncConflictFileDescriptor(
    val relativePath: String,
    val isBinary: Boolean,
    val local: PendingSyncSideMetadata,
    val remote: PendingSyncSideMetadata,
)

internal fun ByteArray.md5Hex(): String {
    val digest = MessageDigest.getInstance("MD5").digest(this)
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}
