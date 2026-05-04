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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import com.lomo.app.feature.common.rememberRetainedVisibleItems
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.domain.model.Memo
import com.lomo.ui.component.common.LazyListItemEntranceState
import com.lomo.ui.component.common.LazyListMotionState
import com.lomo.ui.component.common.WithDraggableScrollbar
import com.lomo.ui.component.common.lazyListMotionItem
import com.lomo.ui.component.common.rememberLazyListMotionState
import com.lomo.ui.component.common.toLazyListMotionViewportSnapshot
import com.lomo.ui.component.menu.MemoMenuState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet

private const val MEMO_LIST_SCROLLBAR_CONTENT_HASH_MULTIPLIER = 31
private const val MEMO_LIST_SCROLLBAR_CONTENT_HASH_SAMPLE_COUNT = 20
private const val PAGING_PLACEHOLDER_KEY_PREFIX = "paging-placeholder-"

private data class MemoPagedListDisplayConfig(
    val dateFormat: String,
    val timeFormat: String,
    val doubleTapEditEnabled: Boolean,
    val freeTextCopyEnabled: Boolean,
)

private data class MemoPagedListActions(
    val onTodoClick: (Memo, Int, Boolean) -> Unit,
    val onMemoDoubleClick: (Memo) -> Unit,
    val onTagClick: (String) -> Unit,
    val onImageClick: (ImageViewerRequest) -> Unit,
    val onShowMemoMenu: (MemoMenuState) -> Unit,
    val onExpandedMemoChange: (String, Boolean) -> Unit,
    val onNewMemoSpacePrepared: (String) -> Unit,
    val onNewMemoRevealConsumed: (String) -> Unit,
)

private data class MemoPagedListMotionOwners(
    val expandedMemoIds: ImmutableSet<String>,
    val deletingIds: ImmutableSet<String>,
    val newMemoInsertAnimationState: NewMemoInsertAnimationState,
    val listMotionState: LazyListMotionState,
)

@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
internal fun MemoListContent(
    pagedMemos: LazyPagingItems<MemoUiModel>,
    knownTotalItemCount: Int,
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
    var expandedMemoIds by rememberSaveable(saver = expandedMemoIdsSaver()) {
        mutableStateOf(persistentSetOf<String>())
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
            snapshotMemos = snapshotMemos,
            knownTotalItemCount = knownTotalItemCount,
            expandedMemoIds = expandedMemoIds,
            onExpandedMemoChange = { memoId, isExpanded ->
                expandedMemoIds = updateExpandedMemoIds(
                    expandedMemoIds = expandedMemoIds,
                    memoId = memoId,
                    isExpanded = isExpanded,
                ).toPersistentSet()
            },
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
    pagedMemos: LazyPagingItems<MemoUiModel>,
    snapshotMemos: ImmutableList<MemoUiModel>,
    knownTotalItemCount: Int,
    expandedMemoIds: ImmutableSet<String>,
    onExpandedMemoChange: (String, Boolean) -> Unit,
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
    val visiblePagedMemos =
        rememberRetainedVisibleItems(
            sourceItems = snapshotMemos,
            retainedIds = deletingIds,
            itemId = { it.memo.id },
            onRetentionSettled = onDeleteAnimationSettled,
        )
    val renderedItemCount = maxOf(visiblePagedMemos.size, pagedMemos.itemCount)
    val scrollbarItemCount =
        remember(knownTotalItemCount, pagedMemos.itemCount, visiblePagedMemos.size) {
            maxOf(knownTotalItemCount, pagedMemos.itemCount, visiblePagedMemos.size, 0)
        }
    val scrollbarContentGeneration =
        rememberMemoListScrollbarContentGeneration(
            snapshotMemos = snapshotMemos,
            deletingIds = deletingIds,
            scrollbarItemCount = scrollbarItemCount,
        )
    val displayConfig =
        MemoPagedListDisplayConfig(
            dateFormat = dateFormat,
            timeFormat = timeFormat,
            doubleTapEditEnabled = doubleTapEditEnabled,
            freeTextCopyEnabled = freeTextCopyEnabled,
        )
    val actions =
        MemoPagedListActions(
            onTodoClick = onTodoClick,
            onMemoDoubleClick = onMemoDoubleClick,
            onTagClick = onTagClick,
            onImageClick = onImageClick,
            onShowMemoMenu = onShowMemoMenu,
            onExpandedMemoChange = onExpandedMemoChange,
            onNewMemoSpacePrepared = onNewMemoSpacePrepared,
            onNewMemoRevealConsumed = onNewMemoRevealConsumed,
        )
    WithDraggableScrollbar(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        enabled = scrollbarEnabled,
        contentGeneration = scrollbarContentGeneration,
        totalItemsCountOverride = scrollbarItemCount,
        scrollTargetItemsCountOverride = renderedItemCount,
    ) {
        val motionItemKeys =
            remember(visiblePagedMemos, pagedMemos.itemCount, renderedItemCount) {
                List(renderedItemCount) { index ->
                    memoListItemKey(index, visiblePagedMemos, pagedMemos)
                }.toPersistentList()
            }
        val listMotionState =
            rememberLazyListMotionState(
                itemKeys = motionItemKeys,
                removingKeys = deletingIds,
                listState = listState,
            )
        val motionOwners =
            MemoPagedListMotionOwners(
                expandedMemoIds = expandedMemoIds,
                deletingIds = deletingIds,
                newMemoInsertAnimationState = newMemoInsertAnimationState,
                listMotionState = listMotionState,
            )

        MemoPagedLazyColumn(
            pagedMemos = pagedMemos,
            visiblePagedMemos = visiblePagedMemos,
            renderedItemCount = renderedItemCount,
            motionOwners = motionOwners,
            listState = listState,
            scrollbarEnabled = scrollbarEnabled,
            displayConfig = displayConfig,
            actions = actions,
        )
    }
}

@Composable
private fun MemoPagedLazyColumn(
    pagedMemos: LazyPagingItems<MemoUiModel>,
    visiblePagedMemos: ImmutableList<MemoUiModel>,
    renderedItemCount: Int,
    motionOwners: MemoPagedListMotionOwners,
    listState: LazyListState,
    scrollbarEnabled: Boolean,
    displayConfig: MemoPagedListDisplayConfig,
    actions: MemoPagedListActions,
) {
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
            count = maxOf(visiblePagedMemos.size, pagedMemos.itemCount),
            key = { index -> memoListItemKey(index, visiblePagedMemos, pagedMemos) },
            contentType = { index -> memoListItemContentType(index, visiblePagedMemos, pagedMemos) },
        ) { index ->
            MemoPagedListRow(
                lazyItemScope = this,
                index = index,
                pagedMemos = pagedMemos,
                visiblePagedMemos = visiblePagedMemos,
                renderedItemCount = renderedItemCount,
                motionOwners = motionOwners,
                listState = listState,
                displayConfig = displayConfig,
                actions = actions,
            )
        }
    }
}

@Composable
private fun MemoPagedListRow(
    lazyItemScope: androidx.compose.foundation.lazy.LazyItemScope,
    index: Int,
    pagedMemos: LazyPagingItems<MemoUiModel>,
    visiblePagedMemos: ImmutableList<MemoUiModel>,
    renderedItemCount: Int,
    motionOwners: MemoPagedListMotionOwners,
    listState: LazyListState,
    displayConfig: MemoPagedListDisplayConfig,
    actions: MemoPagedListActions,
) {
    val pagedUiModel = if (index < pagedMemos.itemCount) pagedMemos[index] else null
    val uiModel = visiblePagedMemos.getOrNull(index) ?: pagedUiModel ?: return

    MemoPagedListItem(
        uiModel = uiModel,
        index = index,
        itemCount = renderedItemCount,
        listState = listState,
        motionOwners = motionOwners,
        displayConfig = displayConfig,
        actions = actions,
        modifier =
            Modifier
                .lazyListMotionItem(
                    lazyItemScope = lazyItemScope,
                    itemKey = uiModel.memo.id,
                    motionState = motionOwners.listMotionState,
                    entranceState = LazyListItemEntranceState.Settled,
                    structureMotionActive = motionOwners.newMemoInsertAnimationState.blocksPlacementSpring,
                )
                .fillMaxWidth(),
    )
}

private fun memoListItemKey(
    index: Int,
    visiblePagedMemos: ImmutableList<MemoUiModel>,
    pagedMemos: LazyPagingItems<MemoUiModel>,
): String =
    memoListItemAt(index, visiblePagedMemos, pagedMemos)?.memo?.id
        ?: "$PAGING_PLACEHOLDER_KEY_PREFIX$index"

private fun memoListItemContentType(
    index: Int,
    visiblePagedMemos: ImmutableList<MemoUiModel>,
    pagedMemos: LazyPagingItems<MemoUiModel>,
): String =
    memoListItemAt(index, visiblePagedMemos, pagedMemos)?.memoListItemContentBucket
        ?: "memo-placeholder"

private fun memoListItemAt(
    index: Int,
    visiblePagedMemos: ImmutableList<MemoUiModel>,
    pagedMemos: LazyPagingItems<MemoUiModel>,
): MemoUiModel? =
    visiblePagedMemos.getOrNull(index)
        ?: if (index < pagedMemos.itemCount) pagedMemos.peek(index) else null

@Composable
private fun rememberMemoListScrollbarContentGeneration(
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

private data class MemoListScrollbarContentGeneration(
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

@Composable
private fun MemoPagedListItem(
    uiModel: MemoUiModel,
    index: Int,
    itemCount: Int,
    listState: LazyListState,
    motionOwners: MemoPagedListMotionOwners,
    displayConfig: MemoPagedListDisplayConfig,
    actions: MemoPagedListActions,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val shouldHoldNewMemoHidden =
        index == 0 &&
            motionOwners.newMemoInsertAnimationState.awaitingInsertedTopMemo &&
            uiModel.memo.id != motionOwners.newMemoInsertAnimationState.previousTopMemoId
    val shouldAnimateNewMemoSpace =
        motionOwners.newMemoInsertAnimationState.blankSpaceMemoId == uiModel.memo.id
    val shouldHoldGapReadyMemoHidden =
        motionOwners.newMemoInsertAnimationState.gapReadyMemoId == uiModel.memo.id
    val shouldAnimateNewMemoReveal =
        motionOwners.newMemoInsertAnimationState.pendingRevealMemoId == uiModel.memo.id
    val bottomSpacing = if (index == itemCount - 1) 0.dp else MEMO_LIST_ITEM_SPACING
    val isExpanded = uiModel.memo.id in motionOwners.expandedMemoIds
    val bottomSpacingPx =
        remember(bottomSpacing, density) {
            with(density) { bottomSpacing.roundToPx() }
        }
    MemoListItem(
        uiModel = uiModel,
        isDeleting = uiModel.memo.id in motionOwners.deletingIds,
        shouldHoldNewMemoHidden = shouldHoldNewMemoHidden,
        shouldHoldGapReadyMemoHidden = shouldHoldGapReadyMemoHidden,
        shouldAnimateNewMemoSpace = shouldAnimateNewMemoSpace,
        shouldAnimateNewMemoReveal = shouldAnimateNewMemoReveal,
        bottomSpacing = bottomSpacing,
        onTodoClick = actions.onTodoClick,
        dateFormat = displayConfig.dateFormat,
        timeFormat = displayConfig.timeFormat,
        onMemoDoubleClick = actions.onMemoDoubleClick,
        doubleTapEditEnabled = displayConfig.doubleTapEditEnabled,
        freeTextCopyEnabled = displayConfig.freeTextCopyEnabled,
        onTagClick = actions.onTagClick,
        onImageClick = actions.onImageClick,
        onShowMemoMenu = actions.onShowMemoMenu,
        isExpanded = isExpanded,
        onExpandedChange = { expanded ->
            motionOwners.listMotionState.beginResizeTransition(
                itemId = uiModel.memo.id,
                expands = expanded,
                snapshot = listState.layoutInfo.toLazyListMotionViewportSnapshot(),
            )
            actions.onExpandedMemoChange(uiModel.memo.id, expanded)
        },
        onNewMemoSpacePrepared = actions.onNewMemoSpacePrepared,
        onNewMemoRevealConsumed = actions.onNewMemoRevealConsumed,
        modifier =
            modifier.then(
                Modifier.onSizeChanged { size ->
                    motionOwners.listMotionState.onItemMeasured(
                        itemId = uiModel.memo.id,
                        itemIndex = index,
                        isRemoving = uiModel.memo.id in motionOwners.deletingIds,
                        heightPx = size.height,
                        bottomSpacingPx = bottomSpacingPx,
                    )
                },
            ),
    )
}

private fun expandedMemoIdsSaver() =
    listSaver<androidx.compose.runtime.MutableState<ImmutableSet<String>>, String>(
        save = { state -> state.value.toList() },
        restore = { restored -> mutableStateOf(restored.toPersistentSet()) },
    )
