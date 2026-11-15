package com.lomo.app.feature.search

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.feature.memo.MemoEditorSessionState
import com.lomo.app.feature.memo.existingMemoEditorSurface
import com.lomo.app.feature.memo.rememberMemoEditorController
import com.lomo.app.util.injectedKoinViewModel
import kotlinx.collections.immutable.toImmutableMap

/**
 * Material 3 Expressive full-screen search surface. Collects the search state, hoists the ViewModel
 * commands once, and closes the keyboard before leaving the page.
 */
@Composable
fun SearchScreen(
    onBackClick: () -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
    onNavigateToShare: (String, Long) -> Unit = { _, _ -> },
    onRequestFocusMemo: (String) -> Unit = {},
    onNavigateToMain: () -> Unit = onBackClick,
    lanShareEnabled: Boolean = true,
    viewModel: SearchViewModel = injectedKoinViewModel(),
) {
    val pagedItems = viewModel.pagedUiMemos.collectAsLazyPagingItems()
    val uiState = collectSearchScreenUiSnapshot(viewModel.snapshotBindings, pagedItems)
    val inputState = rememberTextFieldState(initialText = uiState.query)
    val inputScroll = rememberScrollState()
    LaunchedEffect(uiState.query) {
        if (inputState.text.toString() != uiState.query) {
            inputState.edit { replace(0, length, uiState.query) }
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val searchResultListState = rememberLazyListState()
    val editorController = rememberMemoEditorController()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val collapseSearch: () -> Unit = {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        onBackClick()
    }

    SearchBarSnackbarEffect(
        errorMessage = uiState.errorMessage,
        snackbarHostState = snackbarHostState,
        onClearError = viewModel::clearError,
    )

    val mainViewModel: com.lomo.app.feature.main.MainViewModel = com.lomo.app.util.activityKoinViewModel()
    val memoMenuCommandHandler =
        rememberSearchMemoMenuCommandHandler(
            uiState = uiState,
            editorController = editorController,
            onReportError = viewModel::reportError,
            onDeleteMemo = viewModel::deleteMemo,
            lanShareEnabled = lanShareEnabled,
            onNavigateToShare = onNavigateToShare,
            onRequestFocusMemo = onRequestFocusMemo,
            onNavigateToMain = onNavigateToMain,
            onVersionHistory = { selection -> mainViewModel.loadVersionHistory(selection.memo) },
        )
    val editorSurface =
        existingMemoEditorSurface(
            session =
            MemoEditorSessionState(
                imageDirectory = uiState.imageDirectory,
                ownerDraftId = viewModel.ownerDraftId,
                rootPath = uiState.rootDirectory,
                imageMap = uiState.imageMap,
                dateFormat = uiState.dateFormat,
                timeFormat = uiState.timeFormat,
            ),
            toolbarToolOrder = uiState.inputToolbarToolOrder,
            onUpdateMemo = viewModel::submitMemoUpdate,
            submissionState = viewModel.editorSubmissionState,
            onSaveImages = viewModel::saveImages,
            onToolbarOrderChanged = viewModel.updateInputToolbarToolOrder,
        )

    SearchBars(
        inputState = inputState,
        inputScroll = inputScroll,
        onNavigateToImage = onNavigateToImage,
        uiState = uiState,
        pagedItems = pagedItems,
        memoMenuCommandHandler = memoMenuCommandHandler,
        editorController = editorController,
        editorSurface = editorSurface,
        snackbarHostState = snackbarHostState,
        searchResultListState = searchResultListState,
        onCollapse = collapseSearch,
        commands = viewModel.screenCommands,
    )
}

@Composable
private fun collectSearchScreenUiSnapshot(
    snapshotBindings: SearchUiSnapshotBindings,
    pagedItems: LazyPagingItems<MemoUiModel>,
): SearchScreenUiSnapshot {
    val query by snapshotBindings.query.searchQuery.collectAsStateWithLifecycle()
    val searchMode by snapshotBindings.query.searchMode.collectAsStateWithLifecycle()
    val searchFilter by snapshotBindings.query.searchFilter.collectAsStateWithLifecycle()
    val appPreferences by snapshotBindings.media.appPreferences.collectAsStateWithLifecycle()
    val rootDirectory by snapshotBindings.media.rootDirectory.collectAsStateWithLifecycle()
    val imageDirectory by snapshotBindings.media.imageDirectory.collectAsStateWithLifecycle()
    val imageMap by snapshotBindings.media.imageMap.collectAsStateWithLifecycle()
    val errorMessage by snapshotBindings.media.errorMessage.collectAsStateWithLifecycle()
    val isSearching by snapshotBindings.query.isSearching.collectAsStateWithLifecycle()

    val showLoading = isSearching || pagedItems.loadState.refresh is LoadState.Loading

    return SearchScreenUiSnapshot(
        query = query,
        searchMode = searchMode,
        showLoading = showLoading,
        searchFilter = searchFilter,
        dateFormat = appPreferences.dateFormat,
        timeFormat = appPreferences.timeFormat,
        shareCardShowTime = appPreferences.shareCardShowTime,
        shareCardShowSignature = appPreferences.shareCardShowBrand,
        shareCardSignatureText = appPreferences.shareCardSignatureText,
        customFontPath = appPreferences.customFontPath,
        doubleTapEditEnabled = appPreferences.doubleTapEditEnabled,
        freeTextCopyEnabled = appPreferences.freeTextCopyEnabled,
        inputToolbarToolOrder = appPreferences.inputToolbarToolOrder,
        rootDirectory = rootDirectory,
        imageDirectory = imageDirectory,
        imageMap = remember(imageMap) { imageMap.toImmutableMap() },
        errorMessage = errorMessage,
    )
}

@Composable
private fun SearchBarSnackbarEffect(
    errorMessage: String?,
    snackbarHostState: SnackbarHostState,
    onClearError: () -> Unit,
) {
    LaunchedEffect(errorMessage) {
        errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            onClearError()
        }
    }
}
