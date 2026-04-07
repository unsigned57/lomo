package com.lomo.app.feature.main

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.memo.MemoEditorViewModel
import com.lomo.app.feature.memo.rememberMemoEditorController
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.ui.component.menu.MemoMenuState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
internal fun collectMainScreenUiSnapshot(
    viewModel: MainViewModel,
    sidebarViewModel: SidebarViewModel,
): MainScreenUiSnapshot {
    val memos by viewModel.memos.collectAsStateWithLifecycle()
    val uiMemos by viewModel.uiMemos.collectAsStateWithLifecycle()
    val visibleUiMemos by viewModel.visibleUiMemos.collectAsStateWithLifecycle()
    val searchQuery by sidebarViewModel.searchQuery.collectAsStateWithLifecycle()
    val memoListFilter by viewModel.memoListFilter.collectAsStateWithLifecycle()
    val sidebarUiState by sidebarViewModel.sidebarUiState.collectAsStateWithLifecycle()
    val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingNewMemoCreationRequest by viewModel.pendingNewMemoCreationRequest.collectAsStateWithLifecycle()

    return MainScreenUiSnapshot(
        uiMemos = uiMemos,
        visibleUiMemos = visibleUiMemos,
        hasRawItems = memos.isNotEmpty(),
        searchQuery = searchQuery,
        memoListFilter = memoListFilter,
        sidebarUiState = sidebarUiState,
        dateFormat = appPreferences.dateFormat,
        timeFormat = appPreferences.timeFormat,
        showInputHints = appPreferences.showInputHints,
        doubleTapEditEnabled = appPreferences.doubleTapEditEnabled,
        freeTextCopyEnabled = appPreferences.freeTextCopyEnabled,
        memoActionAutoReorderEnabled = appPreferences.memoActionAutoReorderEnabled,
        memoActionOrder = appPreferences.memoActionOrder,
        quickSaveOnBackEnabled = appPreferences.quickSaveOnBackEnabled,
        shareCardShowTime = appPreferences.shareCardShowTime,
        uiState = uiState,
        pendingNewMemoCreationRequest = pendingNewMemoCreationRequest,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberMainScreenHostState(): MainScreenHostState {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    val listState =
        rememberSaveable(saver = androidx.compose.foundation.lazy.LazyListState.Saver) {
            androidx.compose.foundation.lazy.LazyListState()
        }
    val editorController = rememberMemoEditorController()
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val isExpanded = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    return MainScreenHostState(
        drawerState = drawerState,
        scope = scope,
        snackbarHostState = snackbarHostState,
        scrollBehavior = scrollBehavior,
        listState = listState,
        newMemoInsertAnimationSession = remember { NewMemoInsertAnimationSession() },
        editorController = editorController,
        isExpanded = isExpanded,
        directoryGuideController = rememberMainDirectoryGuideController(),
    )
}

@OptIn(FlowPreview::class)
@Composable
internal fun MainScreenDraftAutosaveEffect(
    editorController: com.lomo.app.feature.memo.MemoEditorController,
    editorViewModel: MemoEditorViewModel,
) {
    LaunchedEffect(editorController) {
        snapshotFlow {
            DraftAutosaveState(
                editingMemoId = editorController.editingMemo?.id,
                text = editorController.inputValue.text,
                isVisible = editorController.isVisible,
            )
        }.debounce(DRAFT_AUTOSAVE_DEBOUNCE_MILLIS)
            .distinctUntilChanged()
            .filter { state -> state.editingMemoId == null && state.isVisible }
            .map { state -> state.text }
            .collect { text -> editorViewModel.saveDraft(text) }
    }
}

@Composable
internal fun MainScreenConflictHost(
    viewModel: MainViewModel,
    conflictViewModel: com.lomo.app.feature.conflict.SyncConflictViewModel,
) {
    com.lomo.app.feature.conflict.SyncConflictDialogHost(conflictViewModel = conflictViewModel)
    LaunchedEffect(Unit) {
        viewModel.syncConflictEvent.collect { conflictSet ->
            conflictViewModel.showConflictDialog(conflictSet)
        }
    }
}

@Composable
internal fun MainScreenContentHost(
    screenState: MainScreenUiSnapshot,
    hostState: MainScreenHostState,
    viewModel: MainViewModel,
    sidebarViewModel: SidebarViewModel,
    editorViewModel: MemoEditorViewModel,
    recordingViewModel: RecordingViewModel,
    unknownErrorMessage: String,
    isRefreshing: Boolean,
    onRefreshingChange: (Boolean) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToTrash: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToTag: (String) -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
    onNavigateToDailyReview: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onNavigateToShare: (String, Long) -> Unit,
) {
    val allTags =
        remember(screenState.sidebarUiState.tags) {
            screenState.sidebarUiState.tags.map { it.name }.sorted()
        }

    MainScreenInteractionBindings(
        viewModel = viewModel,
        editorViewModel = editorViewModel,
        recordingViewModel = recordingViewModel,
        editorController = hostState.editorController,
        directoryGuideController = hostState.directoryGuideController,
        scope = hostState.scope,
        snackbarHostState = hostState.snackbarHostState,
        unknownErrorMessage = unknownErrorMessage,
        shareCardShowTime = screenState.shareCardShowTime,
        quickSaveOnBackEnabled = screenState.quickSaveOnBackEnabled,
        memoActionAutoReorderEnabled = screenState.memoActionAutoReorderEnabled,
        memoActionOrder = screenState.memoActionOrder,
        availableTags = allTags,
        showInputHints = screenState.showInputHints,
        onNavigateToShare = onNavigateToShare,
    ) { showMenu, openEditor ->
        MainScreenNavigationContent(
            screenState = screenState,
            hostState = hostState,
            viewModel = viewModel,
            sidebarViewModel = sidebarViewModel,
            editorViewModel = editorViewModel,
            isRefreshing = isRefreshing,
            onRefreshingChange = onRefreshingChange,
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToTrash = onNavigateToTrash,
            onNavigateToSearch = onNavigateToSearch,
            onNavigateToTag = onNavigateToTag,
            onNavigateToImage = onNavigateToImage,
            onNavigateToDailyReview = onNavigateToDailyReview,
            onNavigateToGallery = onNavigateToGallery,
            onShowMemoMenu = showMenu,
            onOpenEditor = openEditor,
        )
    }

    MainDirectoryGuideHost(
        controller = hostState.directoryGuideController,
        actions =
            MainDirectoryGuideActions(
                onConfirmCreate = { type ->
                    viewModel.createDefaultDirectories(
                        type == DirectorySetupType.Image,
                        type == DirectorySetupType.Voice,
                    )
                },
                onBeforeGoToSettings = hostState.editorController::close,
                onGoToSettings = onNavigateToSettings,
            ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreenNavigationContent(
    screenState: MainScreenUiSnapshot,
    hostState: MainScreenHostState,
    viewModel: MainViewModel,
    sidebarViewModel: SidebarViewModel,
    editorViewModel: MemoEditorViewModel,
    isRefreshing: Boolean,
    onRefreshingChange: (Boolean) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToTrash: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToTag: (String) -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
    onNavigateToDailyReview: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onShowMemoMenu: (MemoMenuState) -> Unit,
    onOpenEditor: (Memo) -> Unit,
) {
    var isMemoFilterSheetVisible by rememberSaveable { mutableStateOf(false) }
    val clearMainFilters = rememberClearMainFiltersAction(viewModel)
    val onHeatmapDateLongPress = rememberMainScreenHeatmapLongPressAction(viewModel, hostState)
    val onScrollToTop = rememberMainScreenScrollToTopAction(hostState)

    MainScreenFilterScrollEffect(
        searchQuery = screenState.searchQuery,
        memoListFilter = screenState.memoListFilter,
        listState = hostState.listState,
    )

    MainScreenNavigationActionHost(
        scope = hostState.scope,
        drawerState = hostState.drawerState,
        isExpanded = hostState.isExpanded,
        canCreateMemo =
            screenState.uiState is MainViewModel.MainScreenState.Ready &&
                screenState.pendingNewMemoCreationRequest == null,
        onCreateMemoUnavailable = {
            if (screenState.uiState !is MainViewModel.MainScreenState.Ready) {
                onNavigateToSettings()
            }
        },
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToTrash = onNavigateToTrash,
        onNavigateToSearch = onNavigateToSearch,
        onNavigateToTag = onNavigateToTag,
        onNavigateToImage = onNavigateToImage,
        onNavigateToDailyReview = onNavigateToDailyReview,
        onNavigateToGallery = onNavigateToGallery,
        onClearSidebarFilters = sidebarViewModel::clearFilters,
        onClearMainFilters = clearMainFilters,
        onOpenMemoFilterPanel = { isMemoFilterSheetVisible = true },
        onOpenCreateMemo = {
            if (screenState.pendingNewMemoCreationRequest == null) {
                hostState.editorController.openForCreate(editorViewModel.draftText.value)
            }
        },
        onRefreshMemos = viewModel.refresh,
        onRefreshingChange = onRefreshingChange,
    ) { actions ->
        MainScreenNavigationRender(
            screenState = screenState,
            hostState = hostState,
            viewModel = viewModel,
            actions = actions,
            isRefreshing = isRefreshing,
            isMemoFilterSheetVisible = isMemoFilterSheetVisible,
            onDismissMemoFilterSheet = { isMemoFilterSheetVisible = false },
            onHeatmapDateLongPress = onHeatmapDateLongPress,
            onScrollToTop = onScrollToTop,
            onShowMemoMenu = onShowMemoMenu,
            onOpenEditor = onOpenEditor,
        )
    }
}

@Composable
private fun MainScreenFilterScrollEffect(
    searchQuery: String,
    memoListFilter: MemoListFilter,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    var previousQuery by rememberSaveable { mutableStateOf("") }
    var previousMemoFilter by remember { mutableStateOf(MemoListFilter()) }

    LaunchedEffect(searchQuery, memoListFilter) {
        val filterChanged =
            previousQuery != searchQuery || previousMemoFilter != memoListFilter
        val hasPreviousUserFilters = previousQuery.isNotEmpty() || previousMemoFilter.hasDateRange
        if (filterChanged && hasPreviousUserFilters) {
            listState.scrollToItem(0)
        }
        previousQuery = searchQuery
        previousMemoFilter = memoListFilter
    }
}

@Composable
private fun rememberClearMainFiltersAction(
    viewModel: MainViewModel,
): () -> Unit =
    remember(viewModel) {
        {
            viewModel.clearMemoDateRange()
        }
    }

@Composable
private fun rememberMainScreenHeatmapLongPressAction(
    viewModel: MainViewModel,
    hostState: MainScreenHostState,
): (LocalDate) -> Unit =
    remember(viewModel, hostState) {
        { date ->
            viewModel.filterMemosByDate(date)
            if (!hostState.isExpanded) {
                hostState.scope.launch { hostState.drawerState.close() }
            }
        }
    }

@Composable
private fun rememberMainScreenScrollToTopAction(
    hostState: MainScreenHostState,
): () -> Unit =
    remember(hostState) {
        {
            hostState.scope.launch {
                if (hostState.listState.firstVisibleItemIndex > MAIN_SCREEN_LIST_SCROLL_SETTLE_INDEX) {
                    hostState.listState.scrollToItem(MAIN_SCREEN_LIST_SCROLL_SETTLE_INDEX)
                }
                hostState.listState.animateScrollToItem(0)
            }
        }
    }
