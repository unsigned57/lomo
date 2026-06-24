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

/**
 * Precomputes the globally unique LazyColumn item keys for the whole rendered range so a duplicate
 * memo id can never reach `items(key = ...)` and crash Compose during fast scroll / paging refresh.
 */
@Composable
internal fun rememberMemoListRenderKeys(
    renderedItemCount: Int,
    visiblePagedMemoStartIndex: Int,
    visiblePagedMemos: ImmutableList<MemoUiModel>,
    pagedMemos: LazyPagingItems<MemoUiModel>,
): ImmutableList<String> =
    remember(visiblePagedMemos, visiblePagedMemoStartIndex, pagedMemos.itemSnapshotList, renderedItemCount) {
        uniqueMemoListRenderKeys(
            List(renderedItemCount) { index ->
                memoListItemKey(
                    index = index,
                    visiblePagedMemoStartIndex = visiblePagedMemoStartIndex,
                    visiblePagedMemos = visiblePagedMemos,
                    pagedMemos = pagedMemos,
                )
            },
        ).toPersistentList()
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

internal fun computeRenderedItemCount(
    snapshotStartIndex: Int,
    visiblePagedMemosSize: Int,
    pagedMemosItemCount: Int,
    knownTotalItemCount: Int,
    pageSize: Int,
): Int = maxOf(
    snapshotStartIndex + visiblePagedMemosSize,
    minOf(knownTotalItemCount, pagedMemosItemCount + pageSize)
)

internal fun computeRetainedExitsCount(
    deletingIds: Set<String>,
    snapshotMemoIds: Set<String>,
): Int {
    return deletingIds.count { it !in snapshotMemoIds }
}



