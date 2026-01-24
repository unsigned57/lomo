package com.lomo.app.feature.main

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import coil3.imageLoader
import coil3.request.ImageRequest
import com.lomo.domain.model.Memo
import com.lomo.ui.component.card.MemoCard
import kotlinx.coroutines.delay

/**
 * Extracted MemoList and MemoItemWrapper from MainScreen.kt
 */
@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class,
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
    onShowMemoMenu: (MemoUiModel) -> Unit,
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
                                val request =
                                    ImageRequest
                                        .Builder(context)
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
        modifier = Modifier.fillMaxSize(),
    ) {
        if (memos.itemCount == 0 && memos.loadState.refresh is LoadState.NotLoading) {
            com.lomo.ui.component.common.EmptyState(
                icon = Icons.AutoMirrored.Rounded.Notes,
                title = "No memos yet",
                description = "Capture your thoughts \nwith the + button below.",
                modifier = Modifier.fillMaxSize(),
            )
        }

        LazyColumn(
            state = listState,
            contentPadding =
                PaddingValues(
                    top = 16.dp,
                    start = 16.dp,
                    end = 16.dp,
                    bottom =
                        WindowInsets.navigationBars
                            .asPaddingValues()
                            .calculateBottomPadding() + 88.dp,
                ),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(
                count = memos.itemCount,
                key = memos.itemKey { it.memo.id },
                contentType = { "memo" },
            ) { index ->
                val uiModel = memos[index]
                if (uiModel != null) {
                    val isDeleting = deletingIds.contains(uiModel.memo.id)

                    // Strictly sequential state management
                    var isVisible by remember { androidx.compose.runtime.mutableStateOf(true) }

                    LaunchedEffect(isDeleting) {
                        if (isDeleting) {
                            delay(300) // Wait strictly for alpha fade
                            isVisible = false // Then trigger shrink
                        } else {
                            isVisible = true
                        }
                    }

                    val alpha by animateFloatAsState(
                        targetValue = if (isDeleting) 0f else 1f,
                        animationSpec =
                            tween(
                                durationMillis = 300,
                                easing = androidx.compose.animation.core.LinearEasing,
                            ),
                        label = "ItemDeleteAlpha",
                    )

                    AnimatedVisibility(
                        visible = isVisible,
                        enter = EnterTransition.None,
                        exit =
                            shrinkVertically(
                                animationSpec =
                                    tween(
                                        durationMillis = 300,
                                    ),
                            ),
                        modifier =
                            Modifier.animateItem(
                                fadeInSpec =
                                    keyframes {
                                        durationMillis = 1000
                                        0f at 0
                                        0f at com.lomo.ui.theme.MotionTokens.DurationLong2
                                        1f at 1000 using com.lomo.ui.theme.MotionTokens.EasingEmphasizedDecelerate
                                    },
                                fadeOutSpec = snap(),
                                placementSpec =
                                    spring<IntOffset>(
                                        stiffness = Spring.StiffnessMedium,
                                    ),
                            ),
                    ) {
                        Column {
                            Box(modifier = Modifier.alpha(alpha)) {
                                MemoItemContent(
                                    uiModel = uiModel,
                                    onTodoClick = onTodoClick,
                                    dateFormat = dateFormat,
                                    timeFormat = timeFormat,
                                    todoOverrides = todoOverrides,
                                    onMemoClick = onMemoClick,
                                    onTagClick = onTagClick,
                                    onImageClick = onImageClick,
                                    onShowMemoMenu = onShowMemoMenu,
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
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
    modifier: Modifier = Modifier,
) {
    val stableTodoClick =
        remember(uiModel.memo) {
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
        menuContent = {},
    )
}
