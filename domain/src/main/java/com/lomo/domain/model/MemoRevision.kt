package com.lomo.domain.model

data class MemoRevision(
    val revisionId: String,
    val parentRevisionId: String?,
    val memoId: String,
    val commitId: String,
    val batchId: String?,
    val createdAt: Long,
    val origin: MemoRevisionOrigin,
    val summary: String,
    val lifecycleState: MemoRevisionLifecycleState,
    val memoContent: String,
    val isCurrent: Boolean,
)

enum class MemoRevisionOrigin {
    LOCAL_CREATE,
    LOCAL_EDIT,
    LOCAL_TRASH,
    LOCAL_RESTORE,
    LOCAL_DELETE,
    IMPORT_REFRESH,
    IMPORT_SYNC,
}

enum class MemoRevisionLifecycleState {
    ACTIVE,
    TRASHED,
    DELETED,
}
