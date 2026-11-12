package com.lomo.app.feature.main

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.paging.compose.LazyPagingItems
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

private const val NEW_MEMO_REVEAL_TIMEOUT_MS = 5_000L

internal suspend fun LazyPagingItems<MemoUiModel>.awaitCreatedMemo(memoId: String, rank: Int): Boolean =
    withTimeoutOrNull(NEW_MEMO_REVEAL_TIMEOUT_MS) {
        snapshotFlow {
            // Access supplies the paging hint even when pinned rows occupy more than one page.
            if (rank < itemCount) this@awaitCreatedMemo[rank]?.run { memo.id } == memoId else false
        }.first { it }
    }.let { ready ->
        if (ready != true) {
            Timber.tag("LomoCreation").w(
                "Paging wait expired: rank=%d count=%d load=%s",
                rank,
                itemCount,
                loadState.refresh,
            )
        }
        ready == true
    }

internal suspend fun LazyListState.positionCreatedMemoForEnter(
    pagedMemos: LazyPagingItems<MemoUiModel>,
    memoId: String,
    rank: Int,
): Boolean = withTimeoutOrNull(NEW_MEMO_REVEAL_TIMEOUT_MS) {
    // A verified head will be composed with its enter on the next layout. Requiring the
    // previous layout to contain it inserts an entire idle frame after return-to-top.
    if (rank == 0 && !canScrollBackward && !isScrollInProgress) {
        return@withTimeoutOrNull pagedMemos.itemCount > 0 && pagedMemos.peek(0)?.run { memo.id } == memoId
    }
    // Preserve the pinned prefix when the new row fits. Long pinned prefixes scroll to the
    // committed row. A layout that already contains the held identity is its acknowledgement;
    // forcing another measure/frame here postpones the first height-animation frame.
    val rankFits = rank == 0 || layoutInfo.visibleItemsInfo.any { it.index == rank }
    val target = if (rankFits) 0 else rank
    // A held row has zero height. Waiting for that row to be visible before releasing
    // its height animation is circular, especially below pinned rows. Paging already
    // witnessed its exact identity; schedule positioning and entry in the same layout.
    if (rank > 0 && !isScrollInProgress) {
        if (!rankFits) requestScrollToItem(target)
        return@withTimeoutOrNull rank < pagedMemos.itemCount && pagedMemos.peek(rank)?.run { memo.id } == memoId
    }
    fun viewportReady(): Boolean {
        val anchorReached = if (target == 0) {
            !canScrollBackward
        } else {
            firstVisibleItemIndex == target && firstVisibleItemScrollOffset == 0
        }
        return !isScrollInProgress && anchorReached && layoutInfo.visibleItemsInfo.any { it.key == memoId }
    }
    if (!viewportReady()) requestScrollToItem(target)
    snapshotFlow { viewportReady() }.first { it }
    rank < pagedMemos.itemCount && pagedMemos.peek(rank)?.run { memo.id } == memoId
}.let { ready ->
    if (ready == null) {
        Timber.tag("LomoCreation").w(
            "Viewport wait expired: rank=%d first=%d offset=%d backward=%s scrolling=%s visible=%s",
            rank,
            firstVisibleItemIndex,
            firstVisibleItemScrollOffset,
            canScrollBackward,
            isScrollInProgress,
            layoutInfo.visibleItemsInfo.map { "${it.index}:${it.size}" },
        )
    }
    ready == true
}
