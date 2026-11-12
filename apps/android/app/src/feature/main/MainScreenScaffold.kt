package com.lomo.app.feature.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.memo.MemoMenuSelection
import com.lomo.domain.model.Memo
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.component.common.EnterAnimationRegistry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainScreenScaffoldContent(
    actions: MainScreenActions,
    sidebarToggle: MainSidebarToggle,
    snackbarHostState: SnackbarHostState,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    searchQuery: String,
    uiState: MainViewModel.MainScreenState,
    pagedUiMemos: LazyPagingItems<MemoUiModel>,
    exitAnimationRegistry: com.lomo.ui.component.common.ExitAnimationRegistry<MemoUiModel>,
    enterAnimationRegistry: EnterAnimationRegistry,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isRefreshing: Boolean,
    onTodoClick: (Memo, com.lomo.domain.model.markdown.MarkdownSourceSpan) -> Unit,
    dateFormat: String,
    timeFormat: String,
    onMemoDoubleClick: (Memo) -> Unit,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    scrollbarEnabled: Boolean,
    onShowMemoMenu: (MemoMenuSelection) -> Unit,
    onReminderClick: (String, String) -> Unit,
    isFilterActive: Boolean,
    isMemoFilterSheetVisible: Boolean,
    memoListFilterController: com.lomo.app.feature.common.MemoListFilterController,
    onDismissMemoFilterSheet: () -> Unit,
    onRetryEngine: () -> Unit,
    onRebuildDerivedIndex: () -> Unit,
    onExportDiagnostics: () -> Unit,
    fab: @Composable () -> Unit,
) {
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets =
        WindowInsets.displayCutout
            .union(WindowInsets.systemBars),
        topBar = {
            MainScreenTopBarSlot(
                actions = actions,
                sidebarToggle = sidebarToggle,
                scrollBehavior = scrollBehavior,
                isFilterActive = isFilterActive,
            )
        },
        floatingActionButton = {
            // Scaffold already accounts for system bars; leave room for downward gestures.
            Box(Modifier.padding(bottom = 40.dp)) { fab() }
        },
        floatingActionButtonPosition = FabPosition.Center,
    ) { padding ->
        MainScreenScaffoldBody(
            padding = padding,
            uiState = uiState,
            searchQuery = searchQuery,
            pagedUiMemos = pagedUiMemos,
            exitAnimationRegistry = exitAnimationRegistry,
            enterAnimationRegistry = enterAnimationRegistry,
            listState = listState,
            isRefreshing = isRefreshing,
            onRefresh = actions.onRefresh,
            onTodoClick = onTodoClick,
            dateFormat = dateFormat,
            timeFormat = timeFormat,
            onMemoDoubleClick = onMemoDoubleClick,
            doubleTapEditEnabled = doubleTapEditEnabled,
            freeTextCopyEnabled = freeTextCopyEnabled,
            scrollbarEnabled = scrollbarEnabled,
            onTagClick = actions.onSidebarTagClick,
            onImageClick = actions.onNavigateToImage,
            onShowMemoMenu = onShowMemoMenu,
            onReminderClick = onReminderClick,
            onSettings = actions.onSettings,
            onRetryEngine = onRetryEngine,
            onRebuildDerivedIndex = onRebuildDerivedIndex,
            onExportDiagnostics = onExportDiagnostics,
        )
    }

    com.lomo.app.feature.common.MemoFilterSheetHost(
        visible = isMemoFilterSheetVisible,
        controller = memoListFilterController,
        onDismiss = onDismissMemoFilterSheet,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreenTopBarSlot(
    actions: MainScreenActions,
    sidebarToggle: MainSidebarToggle,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    isFilterActive: Boolean,
) {
    MainTopBar(
        title = "Lomo",
        scrollBehavior = scrollBehavior,
        menuToggle = sidebarToggle,
        onMenu = actions.onMenuToggle,
        onSearch = actions.onSearch,
        onFilter = actions.onOpenMemoFilterPanel,
        onClearFilter = actions.onClearFilter,
        isFilterActive = isFilterActive,
    )
}

@Composable
private fun MainScreenScaffoldBody(
    padding: PaddingValues,
    uiState: MainViewModel.MainScreenState,
    searchQuery: String,
    pagedUiMemos: LazyPagingItems<MemoUiModel>,
    exitAnimationRegistry: com.lomo.ui.component.common.ExitAnimationRegistry<MemoUiModel>,
    enterAnimationRegistry: EnterAnimationRegistry,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onTodoClick: (Memo, com.lomo.domain.model.markdown.MarkdownSourceSpan) -> Unit,
    dateFormat: String,
    timeFormat: String,
    onMemoDoubleClick: (Memo) -> Unit,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    scrollbarEnabled: Boolean,
    onTagClick: (String) -> Unit,
    onImageClick: (ImageViewerRequest) -> Unit,
    onShowMemoMenu: (MemoMenuSelection) -> Unit,
    onReminderClick: (String, String) -> Unit,
    onSettings: () -> Unit,
    onRetryEngine: () -> Unit,
    onRebuildDerivedIndex: () -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    Box(
        modifier =
        Modifier
            .padding(padding)
            .fillMaxSize()
            .benchmarkAnchorRoot(BenchmarkAnchorContract.MAIN_ROOT),
    ) {
        MainScreenBody(
            uiState = uiState,
            searchQuery = searchQuery,
            pagedUiMemos = pagedUiMemos,
            exitAnimationRegistry = exitAnimationRegistry,
            enterAnimationRegistry = enterAnimationRegistry,
            listState = listState,
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            onTodoClick = onTodoClick,
            dateFormat = dateFormat,
            timeFormat = timeFormat,
            onMemoDoubleClick = onMemoDoubleClick,
            doubleTapEditEnabled = doubleTapEditEnabled,
            freeTextCopyEnabled = freeTextCopyEnabled,
            scrollbarEnabled = scrollbarEnabled,
            onTagClick = onTagClick,
            onImageClick = onImageClick,
            onShowMemoMenu = onShowMemoMenu,
            onReminderClick = onReminderClick,
            onSettings = onSettings,
            onRetryEngine = onRetryEngine,
            onRebuildDerivedIndex = onRebuildDerivedIndex,
            onExportDiagnostics = onExportDiagnostics,
        )
    }
}
