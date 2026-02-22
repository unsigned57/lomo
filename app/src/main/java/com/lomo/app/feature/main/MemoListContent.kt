package com.lomo.app.feature.main

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
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
import coil3.imageLoader
import coil3.request.ImageRequest
import com.lomo.domain.model.Memo
import com.lomo.ui.component.card.MemoCard

@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class,
)
@Composable
internal fun MemoListContent(
    memos: List<MemoUiModel>,
    listState: LazyListState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onTodoClick: (Memo, Int, Boolean) -> Unit,
    dateFormat: String,
    timeFormat: String,
    onMemoClick: (String, String) -> Unit,
    onMemoDoubleClick: (Memo) -> Unit = {},
    doubleTapEditEnabled: Boolean = true,
    onTagClick: (String) -> Unit,
    onImageClick: (String) -> Unit,
    onShowMemoMenu: (MemoUiModel) -> Unit,
) {
    val pullState = rememberPullToRefreshState()
    val context = LocalContext.current
    val imageLoader = context.imageLoader

    LaunchedEffect(listState, memos) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.layoutInfo.visibleItemsInfo.size
        }.collect { (firstVisible, visibleCount) ->
            val preloadRange = (firstVisible + visibleCount)..(firstVisible + visibleCount + 5)
            preloadRange.forEach { index ->
                if (index in memos.indices) {
                    val uiModel = memos[index]
                    uiModel.imageUrls.forEach { url ->
                        if (url.isNotBlank()) {
                            val request =
                                ImageRequest
                                    .Builder(context)
                                    .data(url)
                                    .build()
                            imageLoader.enqueue(request)
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
        if (memos.isEmpty()) {
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
                items = memos,
                key = { it.memo.id },
                contentType = { "memo" },
            ) { uiModel ->
                MemoItemContent(
                    uiModel = uiModel,
                    onTodoClick = onTodoClick,
                    dateFormat = dateFormat,
                    timeFormat = timeFormat,
                    onMemoClick = onMemoClick,
                    onMemoDoubleClick = onMemoDoubleClick,
                    doubleTapEditEnabled = doubleTapEditEnabled,
                    onTagClick = onTagClick,
                    onImageClick = onImageClick,
                    onShowMemoMenu = onShowMemoMenu,
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
                                fadeOutSpec = null,
                                placementSpec = spring(stiffness = Spring.StiffnessLow),
                            ).fillMaxWidth(),
                )
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
    onMemoClick: (String, String) -> Unit,
    onMemoDoubleClick: (Memo) -> Unit,
    doubleTapEditEnabled: Boolean,
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
        todoOverrides = emptyMap(),
        modifier = modifier,
        onClick = { onMemoClick(uiModel.memo.id, uiModel.memo.content) },
        onDoubleClick =
            if (doubleTapEditEnabled) {
                { onMemoDoubleClick(uiModel.memo) }
            } else {
                null
            },
        onTagClick = onTagClick,
        onTodoClick = stableTodoClick,
        onImageClick = onImageClick,
        onMenuClick = { onShowMemoMenu(uiModel) },
        menuContent = {},
    )
}
