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
    val suggestion: SyncMergeSuggestion? = null,
)

/** Owner-computed merge/suggestion wire; neutral across conflict and inbox review surfaces. */
enum class SyncMergeChoice {
    KEEP_LOCAL,
    KEEP_OTHER,
    MERGE_TEXT,
}

data class SyncMergeSuggestion(
    val suggested: SyncMergeChoice?,
    val safe: SyncMergeChoice?,
    val mergedText: String?,
)

fun SyncMergeChoice.toConflictChoice(): SyncConflictResolutionChoice =
    when (this) {
        SyncMergeChoice.KEEP_LOCAL -> SyncConflictResolutionChoice.KEEP_LOCAL
        SyncMergeChoice.KEEP_OTHER -> SyncConflictResolutionChoice.KEEP_REMOTE
        SyncMergeChoice.MERGE_TEXT -> SyncConflictResolutionChoice.MERGE_TEXT
    }

fun SyncMergeChoice.toReviewChoice(): SyncReviewResolutionChoice =
    when (this) {
        SyncMergeChoice.KEEP_LOCAL -> SyncReviewResolutionChoice.KEEP_LOCAL
        SyncMergeChoice.KEEP_OTHER -> SyncReviewResolutionChoice.KEEP_INCOMING
        SyncMergeChoice.MERGE_TEXT -> SyncReviewResolutionChoice.MERGE_TEXT
    }

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
    val suggestion: SyncMergeSuggestion? = null,
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
                    suggestion = file.suggestion,
                )
            },
        timestamp = timestamp,
        kind = SyncReviewSessionKind.INITIAL_IMPORT_PREVIEW,
    )

fun SyncBackendType.supportsDeferredConflictResolution(): Boolean =
    this == SyncBackendType.S3 || this == SyncBackendType.WEBDAV

fun SyncBackendType.supportsDeferredReviewResolution(): Boolean =
    this == SyncBackendType.S3 || this == SyncBackendType.WEBDAV || this == SyncBackendType.INBOX
