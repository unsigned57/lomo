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
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.ui.text.LocalSearchHighlightQuery
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.MotionTokens
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet

@Composable
internal fun SearchScreenContent(
    pagedItems: LazyPagingItems<MemoUiModel>,
    query: String,
    dateFormat: String,
    timeFormat: String,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    exitAnimationRegistry: com.lomo.ui.component.common.ExitAnimationRegistry<MemoUiModel>,
    listState: LazyListState,
    padding: PaddingValues,
    onOpenEditor: (Memo) -> Unit,
    onShowMenu: (MemoMenuSelection) -> Unit,
    onTodoClick: (Memo, Int, Boolean) -> Unit,
) {
    val resultListContentPadding = searchResultListContentPadding(padding)
    val activeExits by exitAnimationRegistry.entries.collectAsStateWithLifecycle()
    val showLoading = pagedItems.loadState.refresh is LoadState.Loading && activeExits.isEmpty()
    val contentState =
        resolveSearchContentState(
            query = query,
            showLoading = showLoading,
            hasResults = pagedItems.itemCount > 0,
            hasActiveExits = activeExits.isNotEmpty(),
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
                    MemoCardList(
                        pagedMemos = pagedItems,
                        dateFormat = dateFormat,
                        timeFormat = timeFormat,
                        doubleTapEditEnabled = doubleTapEditEnabled,
                        freeTextCopyEnabled = freeTextCopyEnabled,
                        onMemoEdit = onOpenEditor,
                        onShowMenu = onShowMenu,
                        onTodoClick = onTodoClick,
                        animation = MemoCardListAnimation.None,
                        exitAnimationRegistry = exitAnimationRegistry,
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
