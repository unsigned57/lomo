package com.lomo.app.feature.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import com.lomo.app.feature.common.rememberRetainedVisibleItems
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.domain.model.Memo
import com.lomo.ui.component.common.WithDraggableScrollbar
import com.lomo.ui.component.menu.MemoMenuState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableList

private const val MEMO_LIST_SCROLLBAR_CONTENT_HASH_MULTIPLIER = 31

@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
internal fun MemoListContent(
    pagedMemos: LazyPagingItems<MemoUiModel>,
    deletingMemoIds: ImmutableSet<String>,
    onDeleteAnimationSettled: (String) -> Unit,
    listState: LazyListState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onTodoClick: (Memo, Int, Boolean) -> Unit,
    dateFormat: String,
    timeFormat: String,
    onTagClick: (String) -> Unit,
    onImageClick: (ImageViewerRequest) -> Unit,
    newMemoInsertAnimationState: NewMemoInsertAnimationState = NewMemoInsertAnimationState(),
    onNewMemoSpacePrepared: (String) -> Unit = {},
    onNewMemoRevealConsumed: (String) -> Unit = {},
    onMemoDoubleClick: (Memo) -> Unit = {},
    doubleTapEditEnabled: Boolean = true,
    freeTextCopyEnabled: Boolean = false,
    scrollbarEnabled: Boolean = true,
    onShowMemoMenu: (MemoMenuState) -> Unit,
) {
    val pullState = rememberPullToRefreshState()
    val snapshotMemos =
        remember(pagedMemos.itemSnapshotList) {
            pagedMemos.itemSnapshotList.items.toImmutableList()
        }

    MemoListPreloadEffect(
        memos = snapshotMemos,
        listState = listState,
    )

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        state = pullState,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
        indicator = {
            MemoListPullToRefreshIndicator(
                state = pullState,
                isRefreshing = isRefreshing,
            )
        },
    ) {
        MemoPagedListColumn(
            snapshotMemos = snapshotMemos,
            deletingIds = deletingMemoIds,
            onDeleteAnimationSettled = onDeleteAnimationSettled,
            newMemoInsertAnimationState = newMemoInsertAnimationState,
            onNewMemoSpacePrepared = onNewMemoSpacePrepared,
            onNewMemoRevealConsumed = onNewMemoRevealConsumed,
            listState = listState,
            onTodoClick = onTodoClick,
            dateFormat = dateFormat,
            timeFormat = timeFormat,
            onMemoDoubleClick = onMemoDoubleClick,
            doubleTapEditEnabled = doubleTapEditEnabled,
            freeTextCopyEnabled = freeTextCopyEnabled,
            scrollbarEnabled = scrollbarEnabled,
            onTagClick = onTagClick,
            onImageClick = onImageClick,
            onShowMemoMenu = onShowMemoMenu,
        )
    }
}

@Composable
private fun MemoPagedListColumn(
    snapshotMemos: ImmutableList<MemoUiModel>,
    deletingIds: ImmutableSet<String>,
    onDeleteAnimationSettled: (String) -> Unit,
    newMemoInsertAnimationState: NewMemoInsertAnimationState,
    onNewMemoSpacePrepared: (String) -> Unit,
    onNewMemoRevealConsumed: (String) -> Unit,
    listState: LazyListState,
    onTodoClick: (Memo, Int, Boolean) -> Unit,
    dateFormat: String,
    timeFormat: String,
    onMemoDoubleClick: (Memo) -> Unit,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    scrollbarEnabled: Boolean,
    onTagClick: (String) -> Unit,
    onImageClick: (ImageViewerRequest) -> Unit,
    onShowMemoMenu: (MemoMenuState) -> Unit,
) {
    val scrollbarContentGeneration = rememberMemoListScrollbarContentGeneration(snapshotMemos, deletingIds)
    WithDraggableScrollbar(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        enabled = scrollbarEnabled,
        contentGeneration = scrollbarContentGeneration,
    ) {
        val visiblePagedMemos =
            rememberRetainedVisibleItems(
                sourceItems = snapshotMemos,
                retainedIds = deletingIds,
                itemId = { it.memo.id },
                onRetentionSettled = onDeleteAnimationSettled,
            )
        val viewportEntryCompensation =
            rememberDeleteViewportEntryCompensation(
                sourceItems = visiblePagedMemos,
                deletingIds = deletingIds,
                listState = listState,
            )

        LazyColumn(
            state = listState,
            contentPadding =
                PaddingValues(
                    top = MEMO_LIST_TOP_PADDING,
                    start = MEMO_LIST_HORIZONTAL_PADDING,
                    end =
                        if (scrollbarEnabled) {
                            MEMO_LIST_END_PADDING_WITH_SCROLLBAR
                        } else {
                            MEMO_LIST_HORIZONTAL_PADDING
                        },
                    bottom =
                        WindowInsets.navigationBars
                            .asPaddingValues()
                            .calculateBottomPadding() + MEMO_LIST_BOTTOM_PADDING,
                ),
            verticalArrangement = Arrangement.Top,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(
                count = visiblePagedMemos.size,
                key = { index -> visiblePagedMemos[index].memo.id },
                contentType = { index -> visiblePagedMemos[index].memoListItemContentBucket },
            ) { index ->
                val uiModel = visiblePagedMemos[index]
                val deleteViewportSharedCompensation =
                    viewportEntryCompensation.sharedTopEntryCompensationFor(uiModel.memo.id)
                val deleteViewportCompensation =
                    deleteViewportSharedCompensation
                        ?: viewportEntryCompensation.compensationFor(uiModel.memo.id)
                val deleteViewportHoldOffset =
                    if (deleteViewportCompensation == null) {
                        viewportEntryCompensation.holdOffsetFor(uiModel.memo.id)
                    } else {
                        null
                    }
                MemoPagedListItem(
                    uiModel = uiModel,
                    index = index,
                    itemCount = visiblePagedMemos.size,
                    deletingIds = deletingIds,
                    newMemoInsertAnimationState = newMemoInsertAnimationState,
                    viewportEntryCompensation = viewportEntryCompensation,
                    onTodoClick = onTodoClick,
                    dateFormat = dateFormat,
                    timeFormat = timeFormat,
                    onMemoDoubleClick = onMemoDoubleClick,
                    doubleTapEditEnabled = doubleTapEditEnabled,
                    freeTextCopyEnabled = freeTextCopyEnabled,
                    onTagClick = onTagClick,
                    onImageClick = onImageClick,
                    onShowMemoMenu = onShowMemoMenu,
                    onNewMemoSpacePrepared = onNewMemoSpacePrepared,
                    onNewMemoRevealConsumed = onNewMemoRevealConsumed,
                    modifier =
                        Modifier
                            .memoListPlacementAnimation(
                                lazyItemScope = this,
                                newMemoInsertAnimationState = newMemoInsertAnimationState,
                                blockPlacementSpringForDeleteViewportEntry =
                                    deleteViewportCompensation != null ||
                                        deleteViewportHoldOffset != null,
                            )
                            .deleteViewportEntryCompensation(
                                compensation = deleteViewportCompensation,
                                holdOffsetPx = deleteViewportHoldOffset,
                                onAnimationConsumed = {
                                    viewportEntryCompensation.clearCompensation(uiModel.memo.id)
                                },
                            )
                            .fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun rememberMemoListScrollbarContentGeneration(
    snapshotMemos: ImmutableList<MemoUiModel>,
    deletingIds: ImmutableSet<String>,
): MemoListScrollbarContentGeneration =
    remember(snapshotMemos, deletingIds) {
        buildMemoListScrollbarContentGeneration(
            snapshotMemos = snapshotMemos,
            deletingIds = deletingIds,
        )
    }

private data class MemoListScrollbarContentGeneration(
    val itemCount: Int,
    val contentHash: Int,
    val deletingIdsHash: Int,
)

private fun buildMemoListScrollbarContentGeneration(
    snapshotMemos: ImmutableList<MemoUiModel>,
    deletingIds: ImmutableSet<String>,
): MemoListScrollbarContentGeneration {
    var contentHash = 1
    snapshotMemos.forEach { uiModel ->
        val memo = uiModel.memo
        contentHash = MEMO_LIST_SCROLLBAR_CONTENT_HASH_MULTIPLIER * contentHash + memo.id.hashCode()
        contentHash = MEMO_LIST_SCROLLBAR_CONTENT_HASH_MULTIPLIER * contentHash + memo.updatedAt.hashCode()
        contentHash = MEMO_LIST_SCROLLBAR_CONTENT_HASH_MULTIPLIER * contentHash + memo.rawContent.hashCode()
        contentHash = MEMO_LIST_SCROLLBAR_CONTENT_HASH_MULTIPLIER * contentHash + uiModel.imageUrls.hashCode()
        contentHash = MEMO_LIST_SCROLLBAR_CONTENT_HASH_MULTIPLIER * contentHash + uiModel.shouldShowExpand.hashCode()
    }
    return MemoListScrollbarContentGeneration(
        itemCount = snapshotMemos.size,
        contentHash = contentHash,
        deletingIdsHash = deletingIds.hashCode(),
    )
}

@Composable
private fun MemoPagedListItem(
    uiModel: MemoUiModel,
    index: Int,
    itemCount: Int,
    deletingIds: ImmutableSet<String>,
    newMemoInsertAnimationState: NewMemoInsertAnimationState,
    viewportEntryCompensation: DeleteViewportEntryCompensationState,
    onTodoClick: (Memo, Int, Boolean) -> Unit,
    dateFormat: String,
    timeFormat: String,
    onMemoDoubleClick: (Memo) -> Unit,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    onTagClick: (String) -> Unit,
    onImageClick: (ImageViewerRequest) -> Unit,
    onShowMemoMenu: (MemoMenuState) -> Unit,
    onNewMemoSpacePrepared: (String) -> Unit,
    onNewMemoRevealConsumed: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val shouldHoldNewMemoHidden =
        index == 0 &&
            newMemoInsertAnimationState.awaitingInsertedTopMemo &&
            uiModel.memo.id != newMemoInsertAnimationState.previousTopMemoId
    val shouldAnimateNewMemoSpace =
        newMemoInsertAnimationState.blankSpaceMemoId == uiModel.memo.id
    val shouldHoldGapReadyMemoHidden =
        newMemoInsertAnimationState.gapReadyMemoId == uiModel.memo.id
    val shouldAnimateNewMemoReveal =
        newMemoInsertAnimationState.pendingRevealMemoId == uiModel.memo.id
    val bottomSpacing = if (index == itemCount - 1) 0.dp else MEMO_LIST_ITEM_SPACING
    val bottomSpacingPx =
        remember(bottomSpacing, density) {
            with(density) { bottomSpacing.roundToPx() }
        }
    MemoListItem(
        uiModel = uiModel,
        isDeleting = uiModel.memo.id in deletingIds,
        shouldHoldNewMemoHidden = shouldHoldNewMemoHidden,
        shouldHoldGapReadyMemoHidden = shouldHoldGapReadyMemoHidden,
        shouldAnimateNewMemoSpace = shouldAnimateNewMemoSpace,
        shouldAnimateNewMemoReveal = shouldAnimateNewMemoReveal,
        bottomSpacing = bottomSpacing,
        onTodoClick = onTodoClick,
        dateFormat = dateFormat,
        timeFormat = timeFormat,
        onMemoDoubleClick = onMemoDoubleClick,
        doubleTapEditEnabled = doubleTapEditEnabled,
        freeTextCopyEnabled = freeTextCopyEnabled,
        onTagClick = onTagClick,
        onImageClick = onImageClick,
        onShowMemoMenu = onShowMemoMenu,
        onNewMemoSpacePrepared = onNewMemoSpacePrepared,
        onNewMemoRevealConsumed = onNewMemoRevealConsumed,
        modifier =
            modifier.then(
                Modifier.onSizeChanged { size ->
                    viewportEntryCompensation.onItemMeasured(
                        itemId = uiModel.memo.id,
                        itemIndex = index,
                        isDeleting = uiModel.memo.id in deletingIds,
                        heightPx = size.height,
                        bottomSpacingPx = bottomSpacingPx,
                    )
                },
            ),
    )
}
