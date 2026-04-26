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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import com.lomo.app.feature.common.DeleteAnimationVisualPolicy
import com.lomo.app.feature.common.resolveDeleteAnimationVisualPolicy
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.domain.model.Memo
import com.lomo.ui.component.common.WithDraggableScrollbar
import com.lomo.ui.component.menu.MemoMenuState
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableList

@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
internal fun MemoListContent(
    pagedMemos: LazyPagingItems<MemoUiModel>,
    deletingMemoIds: ImmutableSet<String>,
    collapsingMemoIds: ImmutableSet<String>,
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
            pagedMemos = pagedMemos,
            deletingIds = deletingMemoIds,
            collapsingIds = collapsingMemoIds,
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
    pagedMemos: LazyPagingItems<MemoUiModel>,
    deletingIds: ImmutableSet<String>,
    collapsingIds: ImmutableSet<String>,
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
    WithDraggableScrollbar(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        enabled = scrollbarEnabled,
    ) {
        LazyColumn(
            state = listState,
            contentPadding =
                PaddingValues(
                    top = MEMO_LIST_TOP_PADDING,
                    start = MEMO_LIST_HORIZONTAL_PADDING,
                    end = MEMO_LIST_HORIZONTAL_PADDING,
                    bottom =
                        WindowInsets.navigationBars
                            .asPaddingValues()
                            .calculateBottomPadding() + MEMO_LIST_BOTTOM_PADDING,
                ),
            verticalArrangement = Arrangement.Top,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(
                count = pagedMemos.itemCount,
                key = { index -> pagedMemos.peek(index)?.memo?.id ?: "paged-memo-$index" },
                contentType = { index ->
                    pagedMemos.peek(index)?.memoListItemContentBucket ?: PAGED_MEMO_PLACEHOLDER_TYPE
                },
            ) { index ->
                val uiModel = pagedMemos[index] ?: return@items
                val deleteAnimationPolicy =
                    resolveDeleteAnimationVisualPolicy(
                        isDeleting = uiModel.memo.id in deletingIds,
                    )
                MemoPagedListItem(
                    uiModel = uiModel,
                    index = index,
                    itemCount = pagedMemos.itemCount,
                    deletingIds = deletingIds,
                    collapsingIds = collapsingIds,
                    newMemoInsertAnimationState = newMemoInsertAnimationState,
                    deleteAnimationPolicy = deleteAnimationPolicy,
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
                                deleteAnimationPolicy = deleteAnimationPolicy,
                                newMemoInsertAnimationState = newMemoInsertAnimationState,
                            )
                            .fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun MemoPagedListItem(
    uiModel: MemoUiModel,
    index: Int,
    itemCount: Int,
    deletingIds: ImmutableSet<String>,
    collapsingIds: ImmutableSet<String>,
    newMemoInsertAnimationState: NewMemoInsertAnimationState,
    deleteAnimationPolicy: DeleteAnimationVisualPolicy,
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
    MemoListItem(
        uiModel = uiModel,
        isDeleting = uiModel.memo.id in deletingIds,
        isCollapsing = uiModel.memo.id in collapsingIds,
        shouldHoldNewMemoHidden = shouldHoldNewMemoHidden,
        shouldHoldGapReadyMemoHidden = shouldHoldGapReadyMemoHidden,
        shouldAnimateNewMemoSpace = shouldAnimateNewMemoSpace,
        shouldAnimateNewMemoReveal = shouldAnimateNewMemoReveal,
        bottomSpacing = if (index == itemCount - 1) 0.dp else MEMO_LIST_ITEM_SPACING,
        deleteAnimationPolicy = deleteAnimationPolicy,
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
        modifier = modifier,
    )
}

private const val PAGED_MEMO_PLACEHOLDER_TYPE = "paged-placeholder"
