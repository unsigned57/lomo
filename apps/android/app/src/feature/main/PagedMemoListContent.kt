package com.lomo.app.feature.main

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.domain.model.Memo
import com.lomo.ui.component.common.WithDraggableScrollbar
import com.lomo.ui.component.common.lomoListItemMotion
import com.lomo.ui.component.common.EnterAnimationRegistry
import com.lomo.ui.component.common.LomoListExitPhase
import com.lomo.ui.component.common.lomoListItemPhaseMotion
import com.lomo.ui.component.common.rememberLomoListEnterState
import com.lomo.ui.component.common.LomoListExitRenderEntry
import com.lomo.ui.component.common.rememberLomoListExitState
import com.lomo.app.feature.memo.MemoMenuSelection
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentSet

private data class MemoPagedListDisplayConfig(
    val dateFormat: String,
    val timeFormat: String,
    val doubleTapEditEnabled: Boolean,
    val freeTextCopyEnabled: Boolean,
)

private data class MemoPagedListActions(
    val onTodoClick: (Memo, com.lomo.domain.model.markdown.MarkdownSourceSpan) -> Unit,
    val onReminderDone: (String, String) -> Unit,
    val onMemoDoubleClick: (Memo) -> Unit,
    val onTagClick: (String) -> Unit,
    val onImageClick: (ImageViewerRequest) -> Unit,
    val onShowMemoMenu: (MemoMenuSelection) -> Unit,
    val onExpandedMemoChange: (String, Boolean) -> Unit,
)



@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
internal fun MemoListContent(
    pagedMemos: LazyPagingItems<MemoUiModel>,
    listState: LazyListState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onTodoClick: (Memo, com.lomo.domain.model.markdown.MarkdownSourceSpan) -> Unit,
    onReminderClick: (String, String) -> Unit,
    dateFormat: String,
    timeFormat: String,
    onTagClick: (String) -> Unit,
    onImageClick: (ImageViewerRequest) -> Unit,
    enterAnimationRegistry: EnterAnimationRegistry = EnterAnimationRegistry(),
    exitAnimationRegistry: com.lomo.ui.component.common.ExitAnimationRegistry<MemoUiModel> =
        com.lomo.ui.component.common.ExitAnimationRegistry(),
    onMemoDoubleClick: (Memo) -> Unit = {},
    doubleTapEditEnabled: Boolean = true,
    freeTextCopyEnabled: Boolean = false,
    scrollbarEnabled: Boolean = true,
    pullToRefreshEnabled: Boolean = true,
    listContentPadding: PaddingValues? = null,
    onShowMemoMenu: (MemoMenuSelection) -> Unit,
) {
    val itemSnapshotList = pagedMemos.itemSnapshotList
    val rawLoadedSnapshot =
        remember(itemSnapshotList) {
            MemoListLoadedSnapshot(
                startIndex = itemSnapshotList.placeholdersBefore,
                memos = itemSnapshotList.items.toImmutableList(),
            )
        }
    val preloadWindow =
        remember(rawLoadedSnapshot) {
            FeedImagePreloadWindow(
                placeholdersBefore = rawLoadedSnapshot.startIndex,
                memos = rawLoadedSnapshot.memos,
            )
        }
    var expandedMemoIds by rememberSaveable(saver = expandedMemoIdsSaver()) {
        mutableStateOf(persistentSetOf<String>())
    }
    val expandedSnapshots = rememberExpandedMemoSnapshots(expandedMemoIds)

    MemoListPreloadEffect(
        loadedWindow = preloadWindow,
        listState = listState,
    )

    val listColumn: @Composable () -> Unit = {
        MemoPagedListColumn(
            pagedMemos = pagedMemos,
            rawLoadedSnapshot = rawLoadedSnapshot,
            expandedMemoIds = expandedMemoIds,
            expandedSnapshots = expandedSnapshots,
            onExpandedMemoChange = { memoId, isExpanded ->
                expandedMemoIds = updateExpandedMemoIds(
                    expandedMemoIds = expandedMemoIds,
                    memoId = memoId,
                    isExpanded = isExpanded,
                ).toPersistentSet()
            },
            exitAnimationRegistry = exitAnimationRegistry,
            enterAnimationRegistry = enterAnimationRegistry,
            listState = listState,
            onTodoClick = onTodoClick,
            onReminderClick = onReminderClick,
            dateFormat = dateFormat,
            timeFormat = timeFormat,
            onMemoDoubleClick = onMemoDoubleClick,
            doubleTapEditEnabled = doubleTapEditEnabled,
            freeTextCopyEnabled = freeTextCopyEnabled,
            scrollbarEnabled = scrollbarEnabled,
            listContentPadding = listContentPadding,
            onTagClick = onTagClick,
            onImageClick = onImageClick,
            onShowMemoMenu = onShowMemoMenu,
        )
    }

    val pullState = rememberPullToRefreshState()
    if (pullToRefreshEnabled) {
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
            listColumn()
        }
    } else {
        listColumn()
    }
}


@Composable
private fun MemoPagedListColumn(
    pagedMemos: LazyPagingItems<MemoUiModel>,
    rawLoadedSnapshot: MemoListLoadedSnapshot<MemoUiModel>,
    expandedMemoIds: ImmutableSet<String>,
    expandedSnapshots: Map<String, MemoUiModel>,
    onExpandedMemoChange: (String, Boolean) -> Unit,
    exitAnimationRegistry: com.lomo.ui.component.common.ExitAnimationRegistry<MemoUiModel>,
    enterAnimationRegistry: EnterAnimationRegistry,
    listState: LazyListState,
    onTodoClick: (Memo, com.lomo.domain.model.markdown.MarkdownSourceSpan) -> Unit,
    onReminderClick: (String, String) -> Unit,
    dateFormat: String,
    timeFormat: String,
    onMemoDoubleClick: (Memo) -> Unit,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    scrollbarEnabled: Boolean,
    listContentPadding: PaddingValues?,
    onTagClick: (String) -> Unit,
    onImageClick: (ImageViewerRequest) -> Unit,
    onShowMemoMenu: (MemoMenuSelection) -> Unit,
) {
    val snapshotMemos = rawLoadedSnapshot.memos
    val snapshotStartIndex = rawLoadedSnapshot.startIndex

    val exitState =
        rememberLomoListExitState(
            registry = exitAnimationRegistry,
            allItems = snapshotMemos,
            itemKey = { it.memo.id },
        )

    val visiblePagedMemos =
        if (exitState.overlayIdle) {
            snapshotMemos
        } else {
            exitState.renderList.map { it.item }.toImmutableList()
        }

    val onItemExitSettled = exitState.onExitSettled

    val enterState =
        rememberLomoListEnterState(
            registry = enterAnimationRegistry,
            allItems = visiblePagedMemos,
            itemKey = { it.memo.id },
        )
    val enteringIds = enterState.enteringIds
    val onItemEnterSettled = enterState.onEnterSettled

    val activeExits by exitAnimationRegistry.entries.collectAsStateWithLifecycle()
    val deletingIds = remember(activeExits) { activeExits.keys.toPersistentSet() }

    val listItemCount = pagedMemos.itemCount
    val scrollbarItemCount = listItemCount
    val materializedItemCount =
        materializedMemoListItemCount(
            placeholdersBefore = snapshotStartIndex,
            loadedCount = visiblePagedMemos.size,
        )

    PagedMemoLazyColumn(
        pagedMemos = pagedMemos,
        visiblePagedMemos = visiblePagedMemos,
        renderList = exitState.renderList,
        overlayIdle = exitState.overlayIdle,
        snapshotStartIndex = snapshotStartIndex,
        renderedItemCount = listItemCount,
        scrollbarItemCount = scrollbarItemCount,
        scrollTargetItemCount = materializedItemCount,
        expandedMemoIds = expandedMemoIds,
        expandedSnapshots = expandedSnapshots,
        listState = listState,
        scrollbarEnabled = scrollbarEnabled,
        listContentPadding = listContentPadding,
        deletingIds = deletingIds,
        snapshotMemos = snapshotMemos,
        dateFormat = dateFormat,
        timeFormat = timeFormat,
        doubleTapEditEnabled = doubleTapEditEnabled,
        freeTextCopyEnabled = freeTextCopyEnabled,
        onTodoClick = onTodoClick,
        onReminderClick = onReminderClick,
        onMemoDoubleClick = onMemoDoubleClick,
        onTagClick = onTagClick,
        onImageClick = onImageClick,
        onShowMemoMenu = onShowMemoMenu,
        onExpandedMemoChange = onExpandedMemoChange,
        onItemExitSettled = onItemExitSettled,
        enteringIds = enteringIds,
        onItemEnterSettled = onItemEnterSettled,
    )
}

@Composable
private fun MemoPagedLazyColumn(
    pagedMemos: LazyPagingItems<MemoUiModel>,
    visiblePagedMemos: ImmutableList<MemoUiModel>,
    renderList: ImmutableList<LomoListExitRenderEntry<MemoUiModel>>,
    visiblePagedMemoStartIndex: Int,
    renderedItemCount: Int,
    expandedMemoIds: ImmutableSet<String>,
    expandedSnapshots: Map<String, MemoUiModel>,
    overlayIdle: Boolean,
    listState: LazyListState,
    scrollbarEnabled: Boolean,
    displayConfig: MemoPagedListDisplayConfig,
    actions: MemoPagedListActions,
    onItemExitSettled: (String) -> Unit,
    enteringIds: ImmutableSet<String>,
    onItemEnterSettled: (String) -> Unit,
    listContentPadding: PaddingValues?,
) {
    val horizontalContentPadding = resolveMemoListHorizontalContentPadding(scrollbarEnabled)
    val renderKeys =
        rememberMemoListRenderKeys(
            visiblePagedMemoStartIndex = visiblePagedMemoStartIndex,
            visiblePagedMemos = visiblePagedMemos,
            pagedMemos = pagedMemos,
        )
    val contentPadding =
        listContentPadding
            ?: PaddingValues(
                  top = MEMO_LIST_TOP_PADDING,
                  start = horizontalContentPadding.start,
                  end = horizontalContentPadding.end,
                  bottom =
                      WindowInsets.navigationBars
                          .asPaddingValues()
                          .calculateBottomPadding() + MEMO_LIST_BOTTOM_PADDING,
            )
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.Top,
        modifier = Modifier.fillMaxSize(),
    ) {
        items(
            count = renderedItemCount,
            key = { index -> renderKeys.keyAt(index) },
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
                renderList = renderList,
                visiblePagedMemos = visiblePagedMemos,
                overlayIdle = overlayIdle,
                expandedSnapshots = expandedSnapshots,
                visiblePagedMemoStartIndex = visiblePagedMemoStartIndex,
                renderedItemCount = renderedItemCount,
                expandedMemoIds = expandedMemoIds,
                displayConfig = displayConfig,
                actions = actions,
                onItemExitSettled = onItemExitSettled,
                enteringIds = enteringIds,
                onItemEnterSettled = onItemEnterSettled,
            )
        }
    }
}

@Composable
private fun MemoPagedListRow(
    lazyItemScope: androidx.compose.foundation.lazy.LazyItemScope,
    index: Int,
    pagedMemos: LazyPagingItems<MemoUiModel>,
    renderList: ImmutableList<LomoListExitRenderEntry<MemoUiModel>>,
    visiblePagedMemos: ImmutableList<MemoUiModel>,
    overlayIdle: Boolean,
    expandedSnapshots: Map<String, MemoUiModel>,
    visiblePagedMemoStartIndex: Int,
    renderedItemCount: Int,
    expandedMemoIds: ImmutableSet<String>,
    displayConfig: MemoPagedListDisplayConfig,
    actions: MemoPagedListActions,
    onItemExitSettled: (String) -> Unit,
    enteringIds: ImmutableSet<String>,
    onItemEnterSettled: (String) -> Unit,
) {
    pagingAccessIndexForRenderedRow(
        index = index,
        pagedItemCount = pagedMemos.itemCount,
        renderedItemCount = renderedItemCount,
    )?.let { accessIndex ->
        pagedMemos[accessIndex]
    }
    val entry =
        if (overlayIdle) {
            null
        } else {
            renderList.getOrNull(index - visiblePagedMemoStartIndex)
        }
    val previewModel =
        entry?.item
            ?: memoListItemAt(
                index = index,
                visiblePagedMemoStartIndex = visiblePagedMemoStartIndex,
                visiblePagedMemos = visiblePagedMemos,
                pagedMemos = pagedMemos,
            )

    if (previewModel == null) {
        MemoPagedListPlaceholderRow(
            lazyItemScope = lazyItemScope,
            index = index,
            itemCount = renderedItemCount,
        )
        return
    }

    val memoId = previewModel.memo.id
    val isExpanded = memoId in expandedMemoIds
    val uiModel = if (isExpanded) expandedSnapshots[memoId] ?: previewModel else previewModel

    val anchoredAfterKey = if (index > 0) {
        if (overlayIdle) {
            memoListItemAt(
                index = index - 1,
                visiblePagedMemoStartIndex = visiblePagedMemoStartIndex,
                visiblePagedMemos = visiblePagedMemos,
                pagedMemos = pagedMemos,
            )?.run { memo.id }
        } else {
            renderList.getOrNull(index - visiblePagedMemoStartIndex - 1)?.run { item.memo.id }
        }
    } else null

    val exitPhase = entry?.exitPhase
    val isExiting = exitPhase != null
    val isEntering = memoId in enteringIds
    MemoPagedListItem(
        uiModel = uiModel,
        index = index,
        itemCount = renderedItemCount,
        expandedMemoIds = expandedMemoIds,
        displayConfig = displayConfig,
        actions = actions,
        exitPhase = exitPhase,
        onExitSettled = { onItemExitSettled((entry?.snapshotMemo ?: previewModel).memo.id) },
        isEntering = isEntering,
        onEnterSettled = { onItemEnterSettled(memoId) },
        modifier =
            Modifier
                .lomoListItemMotion(lazyItemScope, animateAppearance = false, animatePlacement = !isExiting)
                .fillMaxWidth(),
        anchoredAfterKey = anchoredAfterKey,
    )
}

@Composable
private fun MemoPagedListItem(
    uiModel: MemoUiModel,
    index: Int,
    itemCount: Int,
    expandedMemoIds: ImmutableSet<String>,
    displayConfig: MemoPagedListDisplayConfig,
    actions: MemoPagedListActions,
    exitPhase: LomoListExitPhase?,
    onExitSettled: () -> Unit,
    isEntering: Boolean,
    onEnterSettled: () -> Unit,
    modifier: Modifier = Modifier,
    anchoredAfterKey: String? = null,
) {
    val bottomSpacing = if (index == itemCount - 1) 0.dp else MEMO_LIST_ITEM_SPACING
    val isExpanded = uiModel.memo.id in expandedMemoIds
    val isExiting = exitPhase != null

    MemoListItem(
        uiModel = uiModel,
        bottomSpacing = bottomSpacing,
        onTodoClick = actions.onTodoClick,
        onReminderClick =
            createMainReminderDoneClickAction(
                memoId = uiModel.memo.id,
                onReminderDone = actions.onReminderDone,
            ),
        dateFormat = displayConfig.dateFormat,
        timeFormat = displayConfig.timeFormat,
        onMemoDoubleClick = actions.onMemoDoubleClick,
        doubleTapEditEnabled = displayConfig.doubleTapEditEnabled,
        freeTextCopyEnabled = displayConfig.freeTextCopyEnabled,
        onTagClick = actions.onTagClick,
        onImageClick = actions.onImageClick,
        onShowMemoMenu = actions.onShowMemoMenu,
        modifier = modifier.lomoListItemPhaseMotion(
            isEntering = isEntering,
            onEnterSettled = onEnterSettled,
            exitPhase = exitPhase,
            onExitSettled = onExitSettled,
        ),
        isExpanded = isExpanded,
        onExpandedChange = { expanded ->
            actions.onExpandedMemoChange(uiModel.memo.id, expanded)
        },
        anchoredAfterKey = anchoredAfterKey,
        isExiting = isExiting,
    )
}

@Composable
private fun PagedMemoLazyColumn(
    pagedMemos: LazyPagingItems<MemoUiModel>,
    visiblePagedMemos: ImmutableList<MemoUiModel>,
    renderList: ImmutableList<LomoListExitRenderEntry<MemoUiModel>>,
    overlayIdle: Boolean,
    snapshotStartIndex: Int,
    renderedItemCount: Int,
    scrollbarItemCount: Int,
    scrollTargetItemCount: Int,
    expandedMemoIds: ImmutableSet<String>,
    expandedSnapshots: Map<String, MemoUiModel>,
    listState: LazyListState,
    scrollbarEnabled: Boolean,
    listContentPadding: PaddingValues?,
    deletingIds: ImmutableSet<String>,
    snapshotMemos: ImmutableList<MemoUiModel>,
    dateFormat: String,
    timeFormat: String,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    onTodoClick: (Memo, com.lomo.domain.model.markdown.MarkdownSourceSpan) -> Unit,
    onReminderClick: (String, String) -> Unit,
    onMemoDoubleClick: (Memo) -> Unit,
    onTagClick: (String) -> Unit,
    onImageClick: (ImageViewerRequest) -> Unit,
    onShowMemoMenu: (MemoMenuSelection) -> Unit,
    onExpandedMemoChange: (String, Boolean) -> Unit,
    onItemExitSettled: (String) -> Unit,
    enteringIds: ImmutableSet<String>,
    onItemEnterSettled: (String) -> Unit,
) {
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
            onReminderDone = onReminderClick,
            onMemoDoubleClick = onMemoDoubleClick,
            onTagClick = onTagClick,
            onImageClick = onImageClick,
            onShowMemoMenu = onShowMemoMenu,
            onExpandedMemoChange = onExpandedMemoChange,
        )
    WithDraggableScrollbar(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        enabled = scrollbarEnabled,
        contentGeneration = scrollbarContentGeneration,
        totalItemsCountOverride = scrollbarItemCount,
        scrollTargetItemsCountOverride = scrollTargetItemCount,
    ) {
        MemoPagedLazyColumn(
            pagedMemos = pagedMemos,
            visiblePagedMemos = visiblePagedMemos,
            renderList = renderList,
            overlayIdle = overlayIdle,
            expandedSnapshots = expandedSnapshots,
            visiblePagedMemoStartIndex = snapshotStartIndex,
            renderedItemCount = renderedItemCount,
            expandedMemoIds = expandedMemoIds,
            listState = listState,
            scrollbarEnabled = scrollbarEnabled,
            displayConfig = displayConfig,
            actions = actions,
            onItemExitSettled = onItemExitSettled,
            enteringIds = enteringIds,
            onItemEnterSettled = onItemEnterSettled,
            listContentPadding = listContentPadding,
        )
    }
}

private fun expandedMemoIdsSaver() =
    listSaver<androidx.compose.runtime.MutableState<ImmutableSet<String>>, String>(
        save = { state -> state.value.toList() },
        restore = { restored -> mutableStateOf(restored.toPersistentSet()) },
    )
