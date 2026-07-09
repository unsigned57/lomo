package com.lomo.domain.model

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
)

data class SyncConflictResolution(
    val perFileChoices: Map<String, SyncConflictResolutionChoice>,
)

enum class SyncReviewSessionKind {
    INITIAL_IMPORT_PREVIEW,
    SYNC_INBOX_IMPORT_REVIEW,
}

enum class SyncReviewItemState {
    CONTENT_DIFFERENCE,
    READY_TO_IMPORT,
    BLOCKED,
}

enum class SyncReviewResolutionChoice {
    KEEP_LOCAL,
    KEEP_INCOMING,
    MERGE_TEXT,
    SKIP_FOR_NOW,
}

data class SyncReviewItem(
    val relativePath: String,
    val localContent: String?,
    val incomingContent: String?,
    val isBinary: Boolean,
    val localLastModified: Long? = null,
    val incomingLastModified: Long? = null,
    val state: SyncReviewItemState = SyncReviewItemState.CONTENT_DIFFERENCE,
    val message: String? = null,
)

data class SyncReviewSession(
    val source: SyncBackendType,
    val items: List<SyncReviewItem>,
    val timestamp: Long,
    val kind: SyncReviewSessionKind,
)

data class SyncReviewResolution(
    val perItemChoices: Map<String, SyncReviewResolutionChoice>,
)

fun SyncConflictSet.toInitialImportReview(): SyncReviewSession =
    SyncReviewSession(
        source = source,
        items =
            files.map { file ->
                SyncReviewItem(
                    relativePath = file.relativePath,
                    localContent = file.localContent,
                    incomingContent = file.remoteContent,
                    isBinary = file.isBinary,
                    localLastModified = file.localLastModified,
                    incomingLastModified = file.remoteLastModified,
                )
            },
        timestamp = timestamp,
        kind = SyncReviewSessionKind.INITIAL_IMPORT_PREVIEW,
    )

fun SyncBackendType.supportsDeferredConflictResolution(): Boolean =
    this == SyncBackendType.S3 || this == SyncBackendType.WEBDAV

fun SyncBackendType.supportsDeferredReviewResolution(): Boolean =
    this == SyncBackendType.S3 || this == SyncBackendType.WEBDAV || this == SyncBackendType.INBOX
