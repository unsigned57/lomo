package com.lomo.app.feature.main

import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.keyframes
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import coil3.imageLoader
import coil3.request.ImageRequest
import com.lomo.domain.model.Memo
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.animateFloatAsState
import com.lomo.ui.component.card.MemoCard

/**
 * Extracted MemoList and MemoItemWrapper from MainScreen.kt
 */
@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class
)
@Composable
internal fun MemoListContent(
    memos: LazyPagingItems<MemoUiModel>,
    listState: LazyListState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onTodoClick: (Memo, Int, Boolean) -> Unit,
    dateFormat: String,
    timeFormat: String,
    todoOverrides: Map<String, Map<Int, Boolean>>,
    deletingIds: SnapshotStateList<String>,
    onMemoClick: (String, String) -> Unit,
    onTagClick: (String) -> Unit,
    onImageClick: (String) -> Unit,
    onShowMemoMenu: (MemoUiModel) -> Unit
) {
    val pullState = rememberPullToRefreshState()
    val context = LocalContext.current
    val imageLoader = context.imageLoader
    
    // Preload images for memos outside visible area
    LaunchedEffect(listState, memos.itemCount) {
        snapshotFlow { 
            listState.firstVisibleItemIndex to listState.layoutInfo.visibleItemsInfo.size 
        }.collect { (firstVisible, visibleCount) ->
            // Preload images for next 5 items outside visible area
            val preloadRange = (firstVisible + visibleCount)..(firstVisible + visibleCount + 5)
            preloadRange.forEach { index ->
                if (index in 0 until memos.itemCount) {
                    memos.peek(index)?.let { uiModel ->
                        // Use pre-extracted image URLs
                        uiModel.imageUrls.forEach { imageUrl ->
                            if (imageUrl.isNotBlank()) {
                                val request = ImageRequest.Builder(context)
                                    .data(imageUrl)
                                    .build()
                                imageLoader.enqueue(request)
                            }
                        }
                    }
                }
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        state = pullState,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        if (memos.itemCount == 0 && memos.loadState.refresh is LoadState.NotLoading) {
            com.lomo.ui.component.common.EmptyState(
                icon = Icons.AutoMirrored.Rounded.Notes,
                title = "No memos yet",
                description = "Capture your thoughts \nwith the + button below.",
                modifier = Modifier.fillMaxSize()
            )
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                top = 16.dp,
                start = 16.dp,
                end = 16.dp,
                bottom = WindowInsets.navigationBars
                    .asPaddingValues()
                    .calculateBottomPadding() + 88.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(
                count = memos.itemCount,
                key = memos.itemKey { it.memo.id },
                contentType = { "memo" }
            ) { index ->
                val uiModel = memos[index]
                if (uiModel != null) {
                    val isDeleting = deletingIds.contains(uiModel.memo.id)
                    val alpha by animateFloatAsState(
                        targetValue = if (isDeleting) 0f else 1f,
                        animationSpec = tween(
                            durationMillis = com.lomo.ui.theme.MotionTokens.DurationLong2,
                            easing = com.lomo.ui.theme.MotionTokens.EasingEmphasizedAccelerate
                        ),
                        label = "ItemDeleteAlpha"
                    )

                    MemoItemContent(
                        uiModel = uiModel,
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = keyframes {
                                    durationMillis = 1000 // 500ms delay + 500ms fade
                                    0f at 0
                                    0f at com.lomo.ui.theme.MotionTokens.DurationLong2 // Hold at 0 until 500ms
                                    1f at 1000 using com.lomo.ui.theme.MotionTokens.EasingEmphasizedDecelerate // Fade to 1 from 500 to 1000
                                },
                                fadeOutSpec = snap(), // Immediate collapse after manual fade-out
                                placementSpec = spring<IntOffset>(
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                            .alpha(alpha), // Apply manual fade out
                        onTodoClick = onTodoClick,
                        dateFormat = dateFormat,
                        timeFormat = timeFormat,
                        todoOverrides = todoOverrides,
                        onMemoClick = onMemoClick,
                        onTagClick = onTagClick,
                        onImageClick = onImageClick,
                        onShowMemoMenu = onShowMemoMenu
                    )
                }
            }
        }
    }
}

@Composable
internal fun MemoItemContent(
    uiModel: MemoUiModel,
    onTodoClick: (Memo, Int, Boolean) -> Unit,
    dateFormat: String,
    timeFormat: String,
    todoOverrides: Map<String, Map<Int, Boolean>>,
    onMemoClick: (String, String) -> Unit,
    onTagClick: (String) -> Unit,
    onImageClick: (String) -> Unit,
    onShowMemoMenu: (MemoUiModel) -> Unit,
    modifier: Modifier = Modifier
) {
    val stableTodoClick = remember(uiModel.memo) {
        { index: Int, checked: Boolean -> onTodoClick(uiModel.memo, index, checked) }
    }

    MemoCard(
        content = uiModel.memo.content,
        processedContent = uiModel.processedContent,
        precomputedNode = uiModel.markdownNode,
        timestamp = uiModel.memo.timestamp,
        tags = uiModel.tags,
        dateFormat = dateFormat,
        timeFormat = timeFormat,
        todoOverrides = todoOverrides[uiModel.memo.id] ?: emptyMap(),
        modifier = modifier,
        onClick = { onMemoClick(uiModel.memo.id, uiModel.memo.content) },
        onTagClick = onTagClick,
        onTodoClick = stableTodoClick,
        onImageClick = onImageClick,
        onMenuClick = { onShowMemoMenu(uiModel) },
        menuContent = {}
    )
}
