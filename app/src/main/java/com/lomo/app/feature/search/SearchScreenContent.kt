package com.lomo.app.feature.search

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.feature.memo.MemoCardList
import com.lomo.app.feature.memo.MemoCardListAnimation
import com.lomo.app.feature.memo.MemoMenuSelection
import com.lomo.domain.model.Memo
import com.lomo.ui.component.common.EmptyState
import com.lomo.ui.component.common.ExpressiveLoadingIndicator
import com.lomo.ui.text.LocalSearchHighlightQuery
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.MotionTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun SearchScreenContent(
    query: String,
    showLoading: Boolean,
    searchResults: ImmutableList<MemoUiModel>,
    dateFormat: String,
    timeFormat: String,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    deletingMemoIds: ImmutableSet<String>,
    canLoadMore: Boolean,
    listState: LazyListState,
    padding: PaddingValues,
    onOpenEditor: (Memo) -> Unit,
    onShowMenu: (MemoMenuSelection) -> Unit,
    onDeleteAnimationSettled: (String) -> Unit,
    onTodoClick: (Memo, Int, Boolean) -> Unit,
    onLoadMore: () -> Unit,
) {
    val resultListContentPadding = searchResultListContentPadding(padding)
    val contentState =
        resolveSearchContentState(
            query = query,
            showLoading = showLoading,
            hasResults = searchResults.isNotEmpty(),
        )
    androidx.compose.animation.AnimatedContent(
        targetState = contentState,
        transitionSpec = { searchContentTransitionSpec() },
        contentKey = SearchContentState::key,
        label = "SearchContentTransition",
        modifier = Modifier.fillMaxSize(),
    ) { state ->
        when (state) {
            SearchContentState.EmptyInitial ->
                EmptyState(
                    icon = Icons.Default.Search,
                    title = androidx.compose.ui.res.stringResource(R.string.search_empty_initial_title),
                    description = androidx.compose.ui.res.stringResource(R.string.search_empty_initial_desc),
                    modifier = Modifier.padding(padding),
                )

            SearchContentState.Loading ->
                SearchLoadingState(modifier = Modifier.padding(padding))

            SearchContentState.NoResults ->
                EmptyState(
                    icon = Icons.Default.Search,
                    title = androidx.compose.ui.res.stringResource(R.string.search_no_results_title),
                    description = androidx.compose.ui.res.stringResource(R.string.search_no_results_desc),
                    modifier = Modifier.padding(padding),
                )

            SearchContentState.Results ->
                CompositionLocalProvider(
                    LocalSearchHighlightQuery provides query,
                ) {
                    SearchLoadMoreEffect(
                        itemCount = searchResults.size,
                        canLoadMore = canLoadMore,
                        listState = listState,
                        onLoadMore = onLoadMore,
                    )
                    MemoCardList(
                        memos = searchResults,
                        dateFormat = dateFormat,
                        timeFormat = timeFormat,
                        doubleTapEditEnabled = doubleTapEditEnabled,
                        freeTextCopyEnabled = freeTextCopyEnabled,
                        onMemoEdit = onOpenEditor,
                        onShowMenu = onShowMenu,
                        onTodoClick = onTodoClick,
                        animation = MemoCardListAnimation.None,
                        deletingMemoIds = deletingMemoIds,
                        onDeleteAnimationSettled = onDeleteAnimationSettled,
                        listState = listState,
                        contentPadding = resultListContentPadding,
                    )
                }
        }
    }
}

@Composable
private fun searchResultListContentPadding(screenPadding: PaddingValues): PaddingValues {
    val layoutDirection = LocalLayoutDirection.current
    return PaddingValues(
        start = screenPadding.calculateStartPadding(layoutDirection) + AppSpacing.Medium,
        top = screenPadding.calculateTopPadding() + AppSpacing.Medium,
        end = screenPadding.calculateEndPadding(layoutDirection) + AppSpacing.Medium,
        bottom = screenPadding.calculateBottomPadding() + AppSpacing.Medium,
    )
}

@Composable
private fun SearchLoadMoreEffect(
    itemCount: Int,
    canLoadMore: Boolean,
    listState: LazyListState,
    onLoadMore: () -> Unit,
) {
    LaunchedEffect(listState, itemCount, canLoadMore) {
        if (!canLoadMore || itemCount == 0) {
            return@LaunchedEffect
        }
        snapshotFlow {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisibleIndex >= itemCount - SEARCH_LOAD_MORE_LOOKAHEAD_ITEMS
        }.distinctUntilChanged()
            .collect { shouldLoad ->
                if (shouldLoad) {
                    onLoadMore()
                }
            }
    }
}

@OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)
private fun androidx.compose.animation.AnimatedContentTransitionScope<SearchContentState>.searchContentTransitionSpec():
    androidx.compose.animation.ContentTransform =
    androidx.compose.animation.fadeIn(
        animationSpec =
            androidx.compose.animation.core.tween(
                durationMillis = MotionTokens.DurationMedium2,
                easing = MotionTokens.EasingEmphasizedDecelerate,
            ),
    ) togetherWith
        androidx.compose.animation.fadeOut(
            animationSpec =
                androidx.compose.animation.core.tween(
                    durationMillis = MotionTokens.DurationShort4,
                    easing = MotionTokens.EasingEmphasizedAccelerate,
                ),
        )

@Composable
private fun SearchLoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        ExpressiveLoadingIndicator(modifier = Modifier.size(56.dp))
    }
}

private const val SEARCH_LOAD_MORE_LOOKAHEAD_ITEMS = 6
