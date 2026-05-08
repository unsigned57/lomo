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
import androidx.compose.runtime.SideEffect
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
    val contentResizeItemIndex: Int?,
)

private data class MemoListItemContentResizeSignature(
    val processedContent: String,
    val collapsedSummary: String,
    val tags: ImmutableList<String>,
    val imageUrls: ImmutableList<String>,
    val shouldShowExpand: Boolean,
)

private class MemoListContentResizeTracker {
    private var committedSignatures: Map<String, MemoListItemContentResizeSignature> = emptyMap()

    fun changedItemKey(signatures: Map<String, MemoListItemContentResizeSignature>): String? =
        signatures.entries.firstOrNull { (itemKey, signature) ->
            committedSignatures[itemKey]?.let { previousSignature -> previousSignature != signature } == true
        }?.key

    fun commit(signatures: Map<String, MemoListItemContentResizeSignature>) {
        committedSignatures = signatures
    }
}

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
    val itemSnapshotList = pagedMemos.itemSnapshotList
    val snapshotStartIndex = itemSnapshotList.placeholdersBefore
    val snapshotMemos =
        remember(itemSnapshotList) {
            itemSnapshotList.items.toImmutableList()
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
            snapshotStartIndex = snapshotStartIndex,
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
    snapshotStartIndex: Int,
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
        val motionOwners =
            rememberMemoPagedListMotionOwners(
                expandedMemoIds = expandedMemoIds,
                deletingIds = deletingIds,
                newMemoInsertAnimationState = newMemoInsertAnimationState,
                listState = listState,
                renderedItemCount = renderedItemCount,
                visiblePagedMemoStartIndex = snapshotStartIndex,
                visiblePagedMemos = visiblePagedMemos,
                pagedMemos = pagedMemos,
            )

        MemoPagedLazyColumn(
            pagedMemos = pagedMemos,
            visiblePagedMemos = visiblePagedMemos,
            visiblePagedMemoStartIndex = snapshotStartIndex,
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
    visiblePagedMemoStartIndex: Int,
    renderedItemCount: Int,
    motionOwners: MemoPagedListMotionOwners,
    listState: LazyListState,
    scrollbarEnabled: Boolean,
    displayConfig: MemoPagedListDisplayConfig,
    actions: MemoPagedListActions,
) {
    val horizontalContentPadding = resolveMemoListHorizontalContentPadding(scrollbarEnabled)
    LazyColumn(
        state = listState,
        contentPadding =
            PaddingValues(
                top = MEMO_LIST_TOP_PADDING,
                start = horizontalContentPadding.start,
                end = horizontalContentPadding.end,
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
            key = { index ->
                memoListItemKey(
                    index = index,
                    visiblePagedMemoStartIndex = visiblePagedMemoStartIndex,
                    visiblePagedMemos = visiblePagedMemos,
                    pagedMemos = pagedMemos,
                )
            },
            contentType = { index ->
                memoListItemContentType(
                    index = index,
                    visiblePagedMemoStartIndex = visiblePagedMemoStartIndex,
                    visiblePagedMemos = visiblePagedMemos,
                    pagedMemos = pagedMemos,
                )
            },
        ) { index ->
            MemoPagedListRow(
                lazyItemScope = this,
                index = index,
                pagedMemos = pagedMemos,
                visiblePagedMemos = visiblePagedMemos,
                visiblePagedMemoStartIndex = visiblePagedMemoStartIndex,
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
    visiblePagedMemoStartIndex: Int,
    renderedItemCount: Int,
    motionOwners: MemoPagedListMotionOwners,
    listState: LazyListState,
    displayConfig: MemoPagedListDisplayConfig,
    actions: MemoPagedListActions,
) {
    val retainedUiModel = visiblePagedMemos.getOrNull(index - visiblePagedMemoStartIndex)
    val pagedUiModel = if (retainedUiModel == null && index < pagedMemos.itemCount) pagedMemos[index] else null
    val uiModel = retainedUiModel ?: pagedUiModel
    val contentResizeStructureMotionActive = motionOwners.isContentResizeStructureMotionActive(index)

    if (uiModel == null) {
        if (index < pagedMemos.itemCount) {
            MemoPagedListPlaceholderRow(
                lazyItemScope = lazyItemScope,
                index = index,
                itemCount = renderedItemCount,
                listMotionState = motionOwners.listMotionState,
                structureMotionActive =
                    motionOwners.newMemoInsertAnimationState.blocksPlacementSpring ||
                        contentResizeStructureMotionActive,
            )
        }
        return
    }

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
                    structureMotionActive =
                        motionOwners.newMemoInsertAnimationState.blocksPlacementSpring ||
                            contentResizeStructureMotionActive,
                )
                .fillMaxWidth(),
    )
}

@Composable
private fun rememberMemoPagedListMotionOwners(
    expandedMemoIds: ImmutableSet<String>,
    deletingIds: ImmutableSet<String>,
    newMemoInsertAnimationState: NewMemoInsertAnimationState,
    listState: LazyListState,
    renderedItemCount: Int,
    visiblePagedMemoStartIndex: Int,
    visiblePagedMemos: ImmutableList<MemoUiModel>,
    pagedMemos: LazyPagingItems<MemoUiModel>,
): MemoPagedListMotionOwners {
    val motionItemKeys =
        remember(visiblePagedMemos, visiblePagedMemoStartIndex, pagedMemos.itemCount, renderedItemCount) {
            List(renderedItemCount) { index ->
                memoListItemKey(
                    index = index,
                    visiblePagedMemoStartIndex = visiblePagedMemoStartIndex,
                    visiblePagedMemos = visiblePagedMemos,
                    pagedMemos = pagedMemos,
                )
            }.toPersistentList()
        }
    val listMotionState =
        rememberLazyListMotionState(
            itemKeys = motionItemKeys,
            removingKeys = deletingIds,
            listState = listState,
        )
    val contentResizeTracker = remember { MemoListContentResizeTracker() }
    val contentResizeSignatures =
        rememberMemoListItemContentResizeSignatures(
            renderedItemCount = renderedItemCount,
            visiblePagedMemoStartIndex = visiblePagedMemoStartIndex,
            visiblePagedMemos = visiblePagedMemos,
            pagedMemos = pagedMemos,
        )
    val contentResizeItemKey = contentResizeTracker.changedItemKey(contentResizeSignatures)
    val contentResizeItemIndex =
        remember(contentResizeItemKey, motionItemKeys) {
            contentResizeItemKey?.let { itemKey ->
                motionItemKeys.indexOf(itemKey).takeIf { index -> index >= 0 }
            }
        }
    SideEffect {
        contentResizeTracker.commit(contentResizeSignatures)
        contentResizeItemKey?.let { itemKey ->
            listMotionState.beginContentResizeTransition(
                itemId = itemKey,
                snapshot = listState.layoutInfo.toLazyListMotionViewportSnapshot(),
            )
        }
    }
    return MemoPagedListMotionOwners(
        expandedMemoIds = expandedMemoIds,
        deletingIds = deletingIds,
        newMemoInsertAnimationState = newMemoInsertAnimationState,
        listMotionState = listMotionState,
        contentResizeItemIndex = contentResizeItemIndex,
    )
}

private fun MemoPagedListMotionOwners.isContentResizeStructureMotionActive(index: Int): Boolean =
    contentResizeItemIndex?.let { contentResizeIndex -> index >= contentResizeIndex } == true

@Composable
private fun rememberMemoListItemContentResizeSignatures(
    renderedItemCount: Int,
    visiblePagedMemoStartIndex: Int,
    visiblePagedMemos: ImmutableList<MemoUiModel>,
    pagedMemos: LazyPagingItems<MemoUiModel>,
): Map<String, MemoListItemContentResizeSignature> =
    remember(visiblePagedMemos, visiblePagedMemoStartIndex, pagedMemos.itemSnapshotList, renderedItemCount) {
        buildMap {
            repeat(renderedItemCount) { index ->
                val uiModel =
                    memoListItemAt(
                        index = index,
                        visiblePagedMemoStartIndex = visiblePagedMemoStartIndex,
                        visiblePagedMemos = visiblePagedMemos,
                        pagedMemos = pagedMemos,
                    ) ?: return@repeat
                put(uiModel.memo.id, uiModel.toContentResizeSignature())
            }
        }
    }

private fun MemoUiModel.toContentResizeSignature(): MemoListItemContentResizeSignature =
    MemoListItemContentResizeSignature(
        processedContent = processedContent,
        collapsedSummary = collapsedSummary,
        tags = tags,
        imageUrls = imageUrls,
        shouldShowExpand = shouldShowExpand,
    )

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
