package com.lomo.app.feature.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.paging.compose.LazyPagingItems
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toPersistentList
import com.lomo.ui.component.common.uniqueMemoListRenderKeys

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
    )?.run { memo.id }
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

internal data class MemoListRenderKeyWindow(
    val startIndex: Int,
    val keys: ImmutableList<String>,
) {
    fun keyAt(index: Int): String =
        keys.getOrNull(index - startIndex) ?: "$PAGING_PLACEHOLDER_KEY_PREFIX$index"
}

internal fun memoListRenderKeyWindow(
    startIndex: Int,
    windowItemKeys: List<String>,
): MemoListRenderKeyWindow {
    require(startIndex >= 0) { "startIndex must be non-negative" }
    return MemoListRenderKeyWindow(
        startIndex = startIndex,
        keys = uniqueMemoListRenderKeys(windowItemKeys).toPersistentList(),
    )
}

internal fun materializedMemoListItemCount(
    placeholdersBefore: Int,
    loadedCount: Int,
): Int {
    require(placeholdersBefore >= 0) { "placeholdersBefore must be non-negative" }
    require(loadedCount >= 0) { "loadedCount must be non-negative" }
    return placeholdersBefore + loadedCount
}

/**
 * Unique LazyColumn keys for the materialized window. Unloaded ranks stay formulaic so a
 * placeholder-backed `itemCount` never allocates an O(library) key list.
 */
@Composable
internal fun rememberMemoListRenderKeys(
    visiblePagedMemoStartIndex: Int,
    visiblePagedMemos: ImmutableList<MemoUiModel>,
    pagedMemos: LazyPagingItems<MemoUiModel>,
): MemoListRenderKeyWindow =
    remember(visiblePagedMemos, visiblePagedMemoStartIndex, pagedMemos.itemSnapshotList) {
        memoListRenderKeyWindow(
            startIndex = visiblePagedMemoStartIndex,
            windowItemKeys =
                List(visiblePagedMemos.size) { offset ->
                    memoListItemKey(
                        index = visiblePagedMemoStartIndex + offset,
                        visiblePagedMemoStartIndex = visiblePagedMemoStartIndex,
                        visiblePagedMemos = visiblePagedMemos,
                        pagedMemos = pagedMemos,
                    )
                },
        )
    }

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


internal fun computeRetainedExitsCount(
    deletingIds: Set<String>,
    snapshotMemoIds: Set<String>,
): Int {
    return deletingIds.count { it !in snapshotMemoIds }
}

internal fun pagingAccessIndexForRenderedRow(
    index: Int,
    pagedItemCount: Int,
    renderedItemCount: Int,
): Int? {
    if (pagedItemCount <= 0 || index !in 0 until renderedItemCount) {
        return null
    }
    return minOf(index, pagedItemCount - 1)
}

internal data class MemoListLoadedSnapshot<T>(
    val startIndex: Int,
    val memos: ImmutableList<T>,
)

