package com.lomo.app.feature.search

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.FilterListOff
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.lomo.app.R
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.feature.memo.MemoCardList
import com.lomo.app.feature.memo.MemoMenuSelection
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoSearchMode
import com.lomo.ui.component.common.EmptyState
import com.lomo.ui.component.common.ExpressiveContainedLoadingIndicator
import com.lomo.ui.component.common.LomoFilledTonalButton
import com.lomo.ui.text.LocalSearchHighlightQuery
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.util.LocalAppHapticFeedback

@Composable
internal fun SearchScreenContent(
    pagedItems: LazyPagingItems<MemoUiModel>,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
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
    onTodoClick: (Memo, com.lomo.domain.model.markdown.MarkdownSourceSpan) -> Unit,
    searchMode: MemoSearchMode = MemoSearchMode.Fulltext,
    isFilterActive: Boolean = false,
    showLoading: Boolean = pagedItems.loadState.refresh is LoadState.Loading,
    onToggleSearchMode: () -> Unit = {},
    onClearFilter: () -> Unit = {},
) {
    val motionScheme = com.lomo.ui.theme.LocalLomoMotion.current.layout
    val resultListContentPadding = searchResultListContentPadding(padding)
    val activeExits by exitAnimationRegistry.entries.collectAsStateWithLifecycle()
    val effectiveShowLoading = showLoading && activeExits.isEmpty()
    val contentState =
        resolveSearchContentState(
            query = query,
            showLoading = effectiveShowLoading,
            hasResults = pagedItems.itemCount > 0,
            hasActiveExits = activeExits.isNotEmpty(),
        )
    androidx.compose.animation.AnimatedContent(
        targetState = contentState,
        transitionSpec = { searchContentTransitionSpec(motionScheme) },
        contentKey = SearchContentState::key,
        label = "SearchContentTransition",
        modifier = Modifier.fillMaxSize(),
    ) { state ->
        when (state) {
            SearchContentState.EmptyInitial ->
                EmptyState(
                    icon = Icons.Rounded.Search,
                    title = stringResource(R.string.search_empty_initial_title),
                    description = stringResource(R.string.search_empty_initial_desc),
                    modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()),
                )

            SearchContentState.Loading ->
                SearchLoadingState(modifier = Modifier.padding(padding))

            SearchContentState.NoResults ->
                SearchNoResultsState(
                    padding,
                    searchMode,
                    isFilterActive,
                    onToggleSearchMode,
                    onClearFilter,
                )

            SearchContentState.Results ->
                CompositionLocalProvider(
                    LocalSearchHighlightQuery provides query,
                ) {
                    MemoCardList(
                        onImageClick = onNavigateToImage,
                        pagedMemos = pagedItems,
                        dateFormat = dateFormat,
                        timeFormat = timeFormat,
                        doubleTapEditEnabled = doubleTapEditEnabled,
                        freeTextCopyEnabled = freeTextCopyEnabled,
                        onMemoEdit = onOpenEditor,
                        onShowMenu = onShowMenu,
                        onTodoClick = onTodoClick,
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
    val statusBarPadding = WindowInsets.safeDrawing.only(WindowInsetsSides.Top).asPaddingValues().calculateTopPadding()
    return PaddingValues(
        start = screenPadding.calculateStartPadding(layoutDirection) + AppSpacing.Medium,
        top = screenPadding.calculateTopPadding() + statusBarPadding + 76.dp,
        end = screenPadding.calculateEndPadding(layoutDirection) + AppSpacing.Medium,
        bottom = screenPadding.calculateBottomPadding() + 76.dp,
    )
}

private fun AnimatedContentTransitionScope<SearchContentState>.searchContentTransitionSpec(
    motionScheme: MotionScheme,
): ContentTransform = androidx.compose.animation.fadeIn(motionScheme.defaultEffectsSpec()) togetherWith
    androidx.compose.animation.fadeOut(motionScheme.fastEffectsSpec())

@Composable
private fun SearchLoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        ExpressiveContainedLoadingIndicator()
    }
}

@Composable
private fun SearchNoResultsState(
    padding: PaddingValues,
    searchMode: MemoSearchMode,
    isFilterActive: Boolean,
    onToggleSearchMode: () -> Unit,
    onClearFilter: () -> Unit,
) {
    val haptic = LocalAppHapticFeedback.current

    Column(
        modifier =
        Modifier
            .padding(padding)
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyState(
            icon = Icons.Rounded.SearchOff,
            title = stringResource(R.string.search_no_results_title),
            description = stringResource(R.string.search_no_results_desc),
        )
        if (searchMode == MemoSearchMode.Fulltext || isFilterActive) {
            Row(
                modifier = Modifier.padding(top = AppSpacing.Small),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (searchMode == MemoSearchMode.Fulltext) {
                    LomoFilledTonalButton(
                        onClick = {
                            haptic.medium()
                            onToggleSearchMode()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ManageSearch,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.search_mode_switch_to_fuzzy))
                    }
                }
                if (isFilterActive) {
                    LomoFilledTonalButton(
                        onClick = {
                            haptic.medium()
                            onClearFilter()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FilterListOff,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_clear))
                    }
                }
            }
        }
    }
}
