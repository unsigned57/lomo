package com.lomo.app.feature.main

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
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
    memos: LazyPagingItems<Memo>,
    listState: LazyListState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onTodoClick: (Memo, Int, Boolean) -> Unit,
    dateFormat: String,
    timeFormat: String,
    todoOverrides: Map<String, Map<Int, Boolean>>,
    onMemoClick: (String, String) -> Unit,
    onTagClick: (String) -> Unit,
    onImageClick: (String) -> Unit,
    onShowMemoMenu: (MemoUiModel) -> Unit,
    pendingMutations: Map<String, MainViewModel.MemoMutation> = emptyMap(),
    mapper: MemoUiMapper,
    rootDir: String?,
    imageDir: String?,
    imageMap: Map<String, android.net.Uri>,
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
                    memos.peek(index)?.let { memo ->
                        // Resolve images for preloading
                        val imageUrls = mutableListOf<String>()
                        val imageRegex = Regex("!\\[.*?\\]\\((.*?)\\)")
                        imageRegex.findAll(memo.content).forEach { match ->
                            val url = match.groupValues[1]
                            if (url.isNotBlank()) imageUrls.add(url)
                        }

                        imageUrls.forEach { imageUrl ->
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

    val hasItems = memos.itemCount > 0
    val refreshState = memos.loadState.refresh
    val isEmpty = !hasItems && refreshState is LoadState.NotLoading

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        state = pullState,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        if (isEmpty) {
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(
                count = memos.itemCount,
                key = memos.itemKey { it.id },
                contentType = { "memo" },
            ) { index ->
                val memo = memos[index]
                if (memo != null) {
                    // Resolve optimistic mutation state locally
                    val mutation = pendingMutations[memo.id]
                    val isDeleting = mutation is MainViewModel.MemoMutation.Delete
                    val isVisible = !(mutation is MainViewModel.MemoMutation.Delete && mutation.isHidden)

                    if (!isVisible) {
                        Box(Modifier.size(0.dp))
                    } else {
                        val memoToRender =
                            if (mutation is MainViewModel.MemoMutation.Update) {
                                memo.copy(
                                    content = mutation.newContent,
                                )
                            } else {
                                memo
                            }

                        // On-demand Mapping with caching (Pure Paging Stability)
                        // We remember the UiModel based on memo identity, content, and the mutation state.
                        val uiModel =
                            remember(memo.id, memoToRender.content, isDeleting, rootDir, imageDir, imageMap) {
                                mapper.mapToUiModel(
                                    memo = memoToRender,
                                    rootPath = rootDir,
                                    imagePath = imageDir,
                                    imageMap = imageMap,
                                    isDeleting = isDeleting,
                                )
                            }

                        // Time-Deterministic Animation Sequence
                        // Total Duration: 300ms (Fade only)
                        val totalDuration = 300f
                        val fadeDuration = 300f

                        val animationProgress by androidx.compose.runtime.produceState(initialValue = 0f, isDeleting, memo.id) {
                            if (isDeleting) {
                                while (value < 1f) {
                                    val elapsed = System.currentTimeMillis() - mutation.timestamp
                                    value = (elapsed.toFloat() / totalDuration).coerceIn(0f, 1f)
                                    kotlinx.coroutines.delay(16)
                                }
                            } else {
                                value = 0f
                            }
                        }

                        // Derived states from progress
                        val alpha = (1f - (animationProgress / (fadeDuration / totalDuration))).coerceIn(0f, 1f)

                        // Note: We use Box with height scaling instead of AnimatedVisibility
                        // to ensure the height is deterministic based on the global clock.
                        Box(
                            modifier =
                                Modifier
                                    .animateItem(
                                        fadeInSpec =
                                            keyframes {
                                                durationMillis = 600
                                                0f at 0
                                                0f at 300
                                                1f at 600 using com.lomo.ui.theme.MotionTokens.EasingEmphasizedDecelerate
                                            },
                                        fadeOutSpec = snap(),
                                        placementSpec = spring(stiffness = Spring.StiffnessLow),
                                    ).fillMaxWidth()
                                    .alpha(alpha),
                        ) {
                            MemoItemContent(
                                uiModel =
                                    uiModel.copy(
                                        memo = memoToRender,
                                        isDeleting = isDeleting,
                                    ),
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
