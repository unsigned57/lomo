package com.lomo.app.feature.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.paging.compose.LazyPagingItems
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet

private const val MEMO_LIST_SCROLLBAR_CONTENT_HASH_MULTIPLIER = 31
private const val MEMO_LIST_SCROLLBAR_CONTENT_HASH_SAMPLE_COUNT = 20

internal fun memoListItemKey(
    index: Int,
    visiblePagedMemoStartIndex: Int,
    visiblePagedMemos: ImmutableList<MemoUiModel>,
    pagedMemos: LazyPagingItems<MemoUiModel>,
): String =
    memoListItemAt(
        index = index,
        visiblePagedMemoStartIndex = visiblePagedMemoStartIndex,
        visiblePagedMemos = visiblePagedMemos,
        pagedMemos = pagedMemos,
    )?.memo?.id
        ?: "$PAGING_PLACEHOLDER_KEY_PREFIX$index"

internal fun memoListItemContentType(
    index: Int,
    visiblePagedMemoStartIndex: Int,
    visiblePagedMemos: ImmutableList<MemoUiModel>,
    pagedMemos: LazyPagingItems<MemoUiModel>,
): String =
    memoListItemAt(
        index = index,
        visiblePagedMemoStartIndex = visiblePagedMemoStartIndex,
        visiblePagedMemos = visiblePagedMemos,
        pagedMemos = pagedMemos,
    )?.memoListItemContentBucket
        ?: "memo-placeholder"

internal fun memoListItemAt(
    index: Int,
    visiblePagedMemoStartIndex: Int,
    visiblePagedMemos: ImmutableList<MemoUiModel>,
    pagedMemos: LazyPagingItems<MemoUiModel>,
): MemoUiModel? =
    visiblePagedMemos.getOrNull(index - visiblePagedMemoStartIndex)
        ?: if (index < pagedMemos.itemCount) pagedMemos.peek(index) else null

@Composable
internal fun rememberMemoListScrollbarContentGeneration(
    snapshotMemos: ImmutableList<MemoUiModel>,
    deletingIds: ImmutableSet<String>,
    scrollbarItemCount: Int,
): MemoListScrollbarContentGeneration =
    remember(snapshotMemos, deletingIds, scrollbarItemCount) {
        buildMemoListScrollbarContentGeneration(
            snapshotMemos = snapshotMemos,
            deletingIds = deletingIds,
            scrollbarItemCount = scrollbarItemCount,
        )
    }

internal data class MemoListScrollbarContentGeneration(
    val itemCount: Int,
    val contentHash: Int,
    val deletingIdsHash: Int,
)

private fun buildMemoListScrollbarContentGeneration(
    snapshotMemos: ImmutableList<MemoUiModel>,
    deletingIds: ImmutableSet<String>,
    scrollbarItemCount: Int,
): MemoListScrollbarContentGeneration {
    var contentHash = 1
    snapshotMemos.take(MEMO_LIST_SCROLLBAR_CONTENT_HASH_SAMPLE_COUNT).forEach { uiModel ->
        val memo = uiModel.memo
        contentHash = MEMO_LIST_SCROLLBAR_CONTENT_HASH_MULTIPLIER * contentHash + memo.id.hashCode()
        contentHash = MEMO_LIST_SCROLLBAR_CONTENT_HASH_MULTIPLIER * contentHash + memo.updatedAt.hashCode()
        contentHash = MEMO_LIST_SCROLLBAR_CONTENT_HASH_MULTIPLIER * contentHash + memo.rawContent.hashCode()
        contentHash = MEMO_LIST_SCROLLBAR_CONTENT_HASH_MULTIPLIER * contentHash + uiModel.imageUrls.hashCode()
        contentHash = MEMO_LIST_SCROLLBAR_CONTENT_HASH_MULTIPLIER * contentHash + uiModel.shouldShowExpand.hashCode()
    }
    return MemoListScrollbarContentGeneration(
        itemCount = scrollbarItemCount,
        contentHash = contentHash,
        deletingIdsHash = deletingIds.hashCode(),
    )
}
