package com.lomo.app.feature.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.paging.compose.LazyPagingItems
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.feature.common.MemoFilterSheetHost
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.feature.memo.MemoEditorController
import com.lomo.app.feature.memo.MemoEditorSurface
import com.lomo.app.feature.memo.MemoInteractionHost
import com.lomo.app.feature.memo.MemoMenuCommandHandler
import com.lomo.app.feature.memo.rememberAutoSaveCommittedNotifier
import com.lomo.ui.benchmark.benchmarkAnchorRoot

/**
 * Search page results surface: memo interactions, the results
 * list, snackbars, and the search filter sheet.
 */
@Composable
internal fun SearchExpandedContent(
    pagedItems: LazyPagingItems<MemoUiModel>,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
    uiState: SearchScreenUiSnapshot,
    menuCommandHandler: MemoMenuCommandHandler,
    editorController: MemoEditorController,
    editorSurface: MemoEditorSurface,
    snackbarHostState: SnackbarHostState,
    searchResultListState: LazyListState,
    isSearchFilterSheetVisible: Boolean,
    onFilterSheetDismiss: () -> Unit,
    commands: SearchScreenCommands,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    MemoInteractionHost(
        menuCommandHandler = menuCommandHandler,
        controller = editorController,
        editorSurface = editorSurface,
        onEditorOpenFailure = commands.onEditorOpenFailure,
        onAutoSaveCommitted = rememberAutoSaveCommittedNotifier(snackbarHostState),
    ) { showMenu, openEditor ->
        Box(
            modifier =
            Modifier
                .fillMaxSize()
                .benchmarkAnchorRoot(BenchmarkAnchorContract.SEARCH_ROOT),
        ) {
            SearchScreenContent(
                onNavigateToImage = { request ->
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    onNavigateToImage(request)
                },
                pagedItems = pagedItems,
                query = uiState.query,
                showLoading = uiState.showLoading,
                doubleTapEditEnabled = uiState.doubleTapEditEnabled,
                freeTextCopyEnabled = uiState.freeTextCopyEnabled,
                exitAnimationRegistry = commands.exitAnimationRegistry,
                listState = searchResultListState,
                padding = PaddingValues(),
                onOpenEditor = openEditor,
                onShowMenu = { menuState ->
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    showMenu(menuState)
                },
                onTodoClick = commands.onTodoClick,
                searchMode = uiState.searchMode,
                isFilterActive = uiState.searchFilter.isActive,
                onToggleSearchMode = commands.onToggleSearchMode,
                onClearFilter = commands.filterController.clearFilter,
            )
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        MemoFilterSheetHost(
            visible = isSearchFilterSheetVisible,
            controller = commands.filterController,
            onDismiss = onFilterSheetDismiss,
        )
    }
}
