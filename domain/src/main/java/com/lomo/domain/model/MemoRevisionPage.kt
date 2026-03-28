package com.lomo.domain.model

data class MemoRevisionCursor(
    val createdAt: Long,
    val revisionId: String,
)

data class MemoRevisionPage(
    val items: List<MemoRevision>,
    val nextCursor: MemoRevisionCursor?,
) {
    val hasMore: Boolean
        get() = nextCursor != null
}
