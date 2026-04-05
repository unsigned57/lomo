package com.lomo.app.feature.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoSortOption
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.component.menu.MemoMenuState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainScreenScaffoldContent(
    isExpanded: Boolean,
    actions: MainScreenActions,
    snackbarHostState: SnackbarHostState,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    searchQuery: String,
    uiState: MainViewModel.MainScreenState,
    hasRawItems: Boolean,
    uiMemos: List<MemoUiModel>,
    visibleUiMemos: List<MemoUiModel>,
    deletingMemoIds: kotlinx.coroutines.flow.StateFlow<Set<String>>,
    collapsingMemoIds: kotlinx.coroutines.flow.StateFlow<Set<String>>,
    newMemoInsertAnimationState: NewMemoInsertAnimationState,
    onNewMemoSpacePrepared: (String) -> Unit,
    onNewMemoRevealConsumed: (String) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isRefreshing: Boolean,
    onTodoClick: (Memo, Int, Boolean) -> Unit,
    dateFormat: String,
    timeFormat: String,
    onMemoDoubleClick: (Memo) -> Unit,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    onShowMemoMenu: (MemoMenuState) -> Unit,
    isFilterActive: Boolean,
    onScrollToTop: () -> Unit,
    isMemoFilterSheetVisible: Boolean,
    memoListFilter: MemoListFilter,
    onMemoSortOptionSelected: (MemoSortOption) -> Unit,
    onMemoStartDateSelected: (java.time.LocalDate?) -> Unit,
    onMemoEndDateSelected: (java.time.LocalDate?) -> Unit,
    onDismissMemoFilterSheet: () -> Unit,
) {
    val isFabVisible = rememberMainScreenFabVisibility(scrollBehavior)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets =
            WindowInsets.displayCutout
                .union(WindowInsets.systemBars)
                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        topBar = {
            MainScreenTopBarSlot(
                actions = actions,
                scrollBehavior = scrollBehavior,
                isFilterActive = isFilterActive,
                showNavigationIcon = !isExpanded,
            )
        },
        floatingActionButton = {
            MainScreenFabSlot(
                isVisible = isFabVisible,
                onClick = actions.onFabClick,
                onLongClick = onScrollToTop,
            )
        },
        floatingActionButtonPosition = FabPosition.Center,
    ) { padding ->
        MainScreenScaffoldBody(
            padding = padding,
            uiState = uiState,
            searchQuery = searchQuery,
            hasRawItems = hasRawItems,
            uiMemos = uiMemos,
            visibleUiMemos = visibleUiMemos,
            deletingMemoIds = deletingMemoIds,
            collapsingMemoIds = collapsingMemoIds,
            newMemoInsertAnimationState = newMemoInsertAnimationState,
            onNewMemoSpacePrepared = onNewMemoSpacePrepared,
            onNewMemoRevealConsumed = onNewMemoRevealConsumed,
            listState = listState,
            isRefreshing = isRefreshing,
            onRefresh = actions.onRefresh,
            onTodoClick = onTodoClick,
            dateFormat = dateFormat,
            timeFormat = timeFormat,
            onMemoDoubleClick = onMemoDoubleClick,
            doubleTapEditEnabled = doubleTapEditEnabled,
            freeTextCopyEnabled = freeTextCopyEnabled,
            onTagClick = actions.onSidebarTagClick,
            onImageClick = actions.onNavigateToImage,
            onShowMemoMenu = onShowMemoMenu,
            onSettings = actions.onSettings,
        )
    }

    MainScreenFilterSheetHost(
        isVisible = isMemoFilterSheetVisible,
        filter = memoListFilter,
        onSortOptionSelected = onMemoSortOptionSelected,
        onStartDateSelected = onMemoStartDateSelected,
        onEndDateSelected = onMemoEndDateSelected,
        onDismiss = onDismissMemoFilterSheet,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreenTopBarSlot(
    actions: MainScreenActions,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    isFilterActive: Boolean,
    showNavigationIcon: Boolean,
) {
    MainTopBar(
        title = "Lomo",
        scrollBehavior = scrollBehavior,
        onMenu = actions.onMenuOpen,
        onSearch = actions.onSearch,
        onFilter = actions.onOpenMemoFilterPanel,
        onClearFilter = actions.onClearFilter,
        isFilterActive = isFilterActive,
        showNavigationIcon = showNavigationIcon,
    )
}

@Composable
private fun MainScreenFabSlot(
    isVisible: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    MainFab(
        isVisible = isVisible,
        onClick = onClick,
        modifier = Modifier.offset(y = (-16).dp),
        onLongClick = onLongClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberMainScreenFabVisibility(
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
): Boolean {
    val isFabVisible by remember {
        androidx.compose.runtime.derivedStateOf {
            scrollBehavior.state.collapsedFraction < MAIN_SCREEN_FAB_VISIBILITY_THRESHOLD
        }
    }
    return isFabVisible
}

@Composable
private fun MainScreenScaffoldBody(
    padding: PaddingValues,
    uiState: MainViewModel.MainScreenState,
    searchQuery: String,
    hasRawItems: Boolean,
    uiMemos: List<MemoUiModel>,
    visibleUiMemos: List<MemoUiModel>,
    deletingMemoIds: kotlinx.coroutines.flow.StateFlow<Set<String>>,
    collapsingMemoIds: kotlinx.coroutines.flow.StateFlow<Set<String>>,
    newMemoInsertAnimationState: NewMemoInsertAnimationState,
    onNewMemoSpacePrepared: (String) -> Unit,
    onNewMemoRevealConsumed: (String) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onTodoClick: (Memo, Int, Boolean) -> Unit,
    dateFormat: String,
    timeFormat: String,
    onMemoDoubleClick: (Memo) -> Unit,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    onTagClick: (String) -> Unit,
    onImageClick: (ImageViewerRequest) -> Unit,
    onShowMemoMenu: (MemoMenuState) -> Unit,
    onSettings: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .padding(padding)
                .fillMaxSize()
                .benchmarkAnchorRoot(BenchmarkAnchorContract.MAIN_ROOT),
    ) {
        MainScreenAnimatedBody(
            uiState = uiState,
            searchQuery = searchQuery,
            hasRawItems = hasRawItems,
            uiMemos = uiMemos,
            visibleUiMemos = visibleUiMemos,
            deletingMemoIds = deletingMemoIds,
            collapsingMemoIds = collapsingMemoIds,
            newMemoInsertAnimationState = newMemoInsertAnimationState,
            onNewMemoSpacePrepared = onNewMemoSpacePrepared,
            onNewMemoRevealConsumed = onNewMemoRevealConsumed,
            listState = listState,
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            onTodoClick = onTodoClick,
            dateFormat = dateFormat,
            timeFormat = timeFormat,
            onMemoDoubleClick = onMemoDoubleClick,
            doubleTapEditEnabled = doubleTapEditEnabled,
            freeTextCopyEnabled = freeTextCopyEnabled,
            onTagClick = onTagClick,
            onImageClick = onImageClick,
            onShowMemoMenu = onShowMemoMenu,
            onSettings = onSettings,
        )
    }
}

@Composable
private fun MainScreenFilterSheetHost(
    isVisible: Boolean,
    filter: MemoListFilter,
    onSortOptionSelected: (MemoSortOption) -> Unit,
    onStartDateSelected: (java.time.LocalDate?) -> Unit,
    onEndDateSelected: (java.time.LocalDate?) -> Unit,
    onDismiss: () -> Unit,
) {
    if (isVisible) {
        MainMemoFilterSheet(
            filter = filter,
            onSortOptionSelected = onSortOptionSelected,
            onStartDateSelected = onStartDateSelected,
            onEndDateSelected = onEndDateSelected,
            onDismiss = onDismiss,
        )
    }
}
