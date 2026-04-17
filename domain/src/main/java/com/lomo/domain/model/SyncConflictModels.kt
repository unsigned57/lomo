package com.lomo.domain.model

enum class SyncConflictSessionKind {
    STANDARD_CONFLICT,
    INITIAL_SYNC_PREVIEW,
}

enum class SyncConflictResolutionChoice {
    KEEP_LOCAL,
    KEEP_REMOTE,
    MERGE_TEXT,
    SKIP_FOR_NOW,
}

data class SyncConflictFile(
    val relativePath: String,
    val localContent: String?,
    val remoteContent: String?,
    val isBinary: Boolean,
    val localLastModified: Long? = null,
    val remoteLastModified: Long? = null,
)

data class SyncConflictSet(
    val source: SyncBackendType,
    val files: List<SyncConflictFile>,
    val timestamp: Long,
    val sessionKind: SyncConflictSessionKind = SyncConflictSessionKind.STANDARD_CONFLICT,
)

data class SyncConflictResolution(
    val perFileChoices: Map<String, SyncConflictResolutionChoice>,
)

fun SyncBackendType.supportsDeferredConflictResolution(): Boolean =
    this == SyncBackendType.S3 || this == SyncBackendType.WEBDAV || this == SyncBackendType.INBOX
