package com.lomo.app.feature.memo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.image.createImageViewerRequest
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.feature.main.updateExpandedMemoIds
import com.lomo.domain.model.Memo
import com.lomo.ui.component.common.LomoListExitRenderEntry
import com.lomo.ui.component.common.LomoListExitPhase
import com.lomo.ui.component.common.WithDraggableScrollbar
import com.lomo.ui.component.common.lomoListItemExitPhaseMotion
import com.lomo.ui.component.common.lomoListItemMotion
import com.lomo.ui.component.common.rememberLomoListExitState
import com.lomo.ui.component.common.ExitAnimationRegistry
import com.lomo.ui.component.common.rememberUniqueExitRenderListKeys
import com.lomo.ui.component.common.SkeletonMemoItem
import androidx.paging.compose.LazyPagingItems
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentSet

enum class MemoCardListAnimation {
    None,
    FadeIn,
    Placement,
}

private val MEMO_CARD_LIST_ITEM_SPACING = 12.dp

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MemoCardList(
    pagedMemos: LazyPagingItems<MemoUiModel>,
    dateFormat: String,
    timeFormat: String,
    doubleTapEditEnabled: Boolean,
    onMemoEdit: (Memo) -> Unit,
    onShowMenu: (MemoMenuSelection) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState? = null,
    freeTextCopyEnabled: Boolean = false,
    onImageClick: (ImageViewerRequest) -> Unit = {},
    onTodoClick: ((Memo, Int, Boolean) -> Unit)? = null,
    onTagClick: (String) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(16.dp),
    animation: MemoCardListAnimation = MemoCardListAnimation.FadeIn,
    showScrollbar: Boolean = false,
    exitAnimationRegistry: ExitAnimationRegistry<MemoUiModel> =
        remember { ExitAnimationRegistry() },
) {
    val resolvedListState = listState ?: rememberLazyListState()
    val itemSnapshotList = pagedMemos.itemSnapshotList
    val snapshotStartIndex = itemSnapshotList.placeholdersBefore
    val snapshotMemos = remember(itemSnapshotList) {
        itemSnapshotList.items.toImmutableList()
    }

    val exitState =
        rememberLomoListExitState(
            registry = exitAnimationRegistry,
            allItems = snapshotMemos,
            itemKey = { it.memo.id },
        )

    val onItemExitSettled = exitState.onExitSettled
 
    var expandedMemoIds by rememberSaveable(saver = memoCardExpandedMemoIdsSaver()) {
        mutableStateOf(persistentSetOf<String>())
    }
 
    val listContent: @Composable () -> Unit = {
        val totalItemCount = maxOf(snapshotStartIndex + exitState.renderList.size, pagedMemos.itemCount)
        val uniqueKeys = rememberUniqueExitRenderListKeys(
            totalItemCount = totalItemCount,
            snapshotStartIndex = snapshotStartIndex,
            renderList = exitState.renderList,
            itemKey = { it.memo.id },
            peekItem = { index -> pagedMemos.peek(index) },
            itemSnapshotList = pagedMemos.itemSnapshotList
        )
        LazyColumn(
            state = resolvedListState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.Top,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(
                count = totalItemCount,
                key = { index -> uniqueKeys.getOrElse(index) { "fallback-$index" } },
            ) { index ->
                if (index < pagedMemos.itemCount) {
                    pagedMemos[index]
                }
                val entry = exitState.renderList.getOrNull(index - snapshotStartIndex)
                val uiModel = entry?.item
                if (uiModel != null) {
                    val anchoredAfterKey = if (index > 0) {
                        val prevEntry = exitState.renderList.getOrNull(index - snapshotStartIndex - 1)
                        prevEntry?.item?.memo?.id
                    } else null
                    MemoCardListItem(
                        uiModel = uiModel,
                        isLastItem = index == totalItemCount - 1,
                        dateFormat = dateFormat,
                        timeFormat = timeFormat,
                        doubleTapEditEnabled = doubleTapEditEnabled,
                        freeTextCopyEnabled = freeTextCopyEnabled,
                        animation = animation,
                        isExpanded = uiModel.memo.id in expandedMemoIds,
                        onExpandedChange = { expanded ->
                            expandedMemoIds =
                                updateExpandedMemoIds(
                                    expandedMemoIds = expandedMemoIds,
                                    memoId = uiModel.memo.id,
                                    isExpanded = expanded,
                                ).toPersistentSet()
                        },
                        onMemoEdit = onMemoEdit,
                        onShowMenu = onShowMenu,
                        onImageClick = onImageClick,
                        onTodoClick = onTodoClick,
                        onTagClick = onTagClick,
                        exitPhase = entry.exitPhase,
                        onExitSettled = { onItemExitSettled(entry.snapshotMemo.memo.id) },
                        lazyItemScope = this,
                        anchoredAfterKey = anchoredAfterKey,
                    )
                } else {
                    val bottomSpacing = if (index == totalItemCount - 1) 0.dp else MEMO_CARD_LIST_ITEM_SPACING
                    SkeletonMemoItem(
                        modifier = Modifier
                            .lomoListItemMotion(this)
                            .padding(bottom = bottomSpacing)
                    )
                }
            }
        }
    }

    if (showScrollbar) {
        WithDraggableScrollbar(
            state = resolvedListState,
            modifier = modifier.fillMaxSize(),
        ) {
            listContent()
        }
    } else {
        Box(modifier = modifier.fillMaxSize()) {
            listContent()
        }
    }
}


@Composable
private fun MemoCardListItem(
    uiModel: MemoUiModel,
    isLastItem: Boolean,
    dateFormat: String,
    timeFormat: String,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    animation: MemoCardListAnimation,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onMemoEdit: (Memo) -> Unit,
    onShowMenu: (MemoMenuSelection) -> Unit,
    onImageClick: (ImageViewerRequest) -> Unit,
    onTodoClick: ((Memo, Int, Boolean) -> Unit)?,
    onTagClick: (String) -> Unit,
    exitPhase: LomoListExitPhase?,
    onExitSettled: () -> Unit,
    lazyItemScope: androidx.compose.foundation.lazy.LazyItemScope,
    anchoredAfterKey: String? = null,
) {
    val bottomSpacing = if (isLastItem) 0.dp else MEMO_CARD_LIST_ITEM_SPACING
    val isExiting = exitPhase != null
    val stableImageClick =
        remember(uiModel.imageUrls, onImageClick) {
            { url: String ->
                onImageClick(
                    createImageViewerRequest(
                        imageUrls = uiModel.imageUrls,
                        clickedUrl = url,
                        memoId = uiModel.memo.id,
                    ),
                )
            }
        }
    val stableTodoClick: ((Int, Boolean) -> Unit)? =
        remember(uiModel.memo, onTodoClick) {
            if (onTodoClick == null) {
                null
            } else {
                { lineIndex: Int, checked: Boolean ->
                    onTodoClick.invoke(uiModel.memo, lineIndex, checked)
                }
            }
        }

    val itemModifier =
        if (animation == MemoCardListAnimation.None) {
            Modifier.padding(bottom = bottomSpacing)
        } else {
            Modifier
                .lomoListItemMotion(lazyItemScope, animatePlacement = !isExiting)
                .padding(bottom = bottomSpacing)
        }

    MemoCardEntry(
        uiModel = uiModel,
        dateFormat = dateFormat,
        timeFormat = timeFormat,
        doubleTapEditEnabled = doubleTapEditEnabled,
        freeTextCopyEnabled = freeTextCopyEnabled,
        onMemoEdit = onMemoEdit,
        onShowMenu = onShowMenu,
        onImageClick = stableImageClick,
        onTodoClick = stableTodoClick,
        onTagClick = onTagClick,
        modifier = itemModifier.lomoListItemExitPhaseMotion(
            exitPhase = exitPhase,
            onExitSettled = onExitSettled,
        ),
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        anchoredAfterKey = anchoredAfterKey,
        isExiting = isExiting,
    )
}

private fun memoCardExpandedMemoIdsSaver() =
    listSaver<androidx.compose.runtime.MutableState<ImmutableSet<String>>, String>(
        save = { state -> state.value.toList() },
        restore = { restored -> mutableStateOf(restored.toPersistentSet()) },
    )
