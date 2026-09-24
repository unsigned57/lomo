package com.lomo.app.feature.main

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.window.core.layout.WindowSizeClass
import com.lomo.app.feature.conflict.RemoteSyncConflictPollHost
import com.lomo.app.feature.conflict.ReviewSyncProviders
import com.lomo.app.feature.conflict.SyncConflictDialogController
import com.lomo.app.feature.conflict.SyncConflictStateHost
import com.lomo.app.feature.conflict.autoResolveSafeConflicts
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.memo.rememberMemoEditorController
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.app.feature.memo.MemoMenuSelection
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalFoundationApi::class)
private val MAIN_MEMO_LIST_CACHE_WINDOW =
    LazyLayoutCacheWindow(
        aheadFraction = 1f,
        behindFraction = 0.5f,
    )

@OptIn(ExperimentalFoundationApi::class)
private val MainMemoListStateSaver =
    listSaver<LazyListState, Int>(
        save = { state ->
            listOf(
                state.firstVisibleItemIndex,
                state.firstVisibleItemScrollOffset,
            )
        },
        restore = { restored ->
            LazyListState(
                cacheWindow = MAIN_MEMO_LIST_CACHE_WINDOW,
                firstVisibleItemIndex = restored[0],
                firstVisibleItemScrollOffset = restored[1],
            )
        },
    )

@Composable
internal fun collectMainScreenUiSnapshot(
    dependencies: MainScreenDependencies,
): MainScreenUiSnapshot {
    val searchQuery by dependencies.sidebarViewModel.searchQuery.collectAsStateWithLifecycle()
    val memoListFilter by dependencies.mainViewModel.memoListFilter.collectAsStateWithLifecycle()
    val sidebarUiState by dependencies.sidebarViewModel.sidebarUiState.collectAsStateWithLifecycle()
    val appPreferences by dependencies.mainViewModel.appPreferences.collectAsStateWithLifecycle()
    val uiState by dependencies.mainViewModel.uiState.collectAsStateWithLifecycle()
    val pendingNewMemoCreationEvents by
        dependencies.mainViewModel.pendingNewMemoCreationEvents.collectAsStateWithLifecycle()
    val pendingNewMemoCreationEvent = pendingNewMemoCreationEvents.lastOrNull()

    return MainScreenUiSnapshot(
        searchQuery = searchQuery,
        memoListFilter = memoListFilter,
        sidebarUiState = sidebarUiState,
        dateFormat = appPreferences.dateFormat,
        timeFormat = appPreferences.timeFormat,
        calendarHeatmapThresholds = appPreferences.calendarHeatmapThresholds,
        showInputHints = appPreferences.showInputHints,
        doubleTapEditEnabled = appPreferences.doubleTapEditEnabled,
        freeTextCopyEnabled = appPreferences.freeTextCopyEnabled,
        memoActionAutoReorderEnabled = appPreferences.memoActionAutoReorderEnabled,
        autoOpenInputOnForeground = appPreferences.autoOpenInputOnForeground,
        memoActionOrder = appPreferences.memoActionOrder,
        inputToolbarToolOrder = appPreferences.inputToolbarToolOrder,
        quickSaveOnBackEnabled = appPreferences.quickSaveOnBackEnabled,
        scrollbarEnabled = appPreferences.scrollbarEnabled,
        shareCardShowTime = appPreferences.shareCardShowTime,
        shareCardShowSignature = appPreferences.shareCardShowBrand,
        shareCardSignatureText = appPreferences.shareCardSignatureText,
        customFontPath = appPreferences.customFontPath,
        uiState = uiState,
        pendingNewMemoCreationEvent = pendingNewMemoCreationEvent,
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun rememberMainScreenHostState(): MainScreenHostState {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    val listState =
        rememberSaveable(saver = MainMemoListStateSaver) {
            LazyListState(
                cacheWindow = MAIN_MEMO_LIST_CACHE_WINDOW,
            )
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
        editorController = editorController,
        isExpanded = isExpanded,
        directoryGuideController = rememberMainDirectoryGuideController(),
    )
}

@OptIn(FlowPreview::class)
@Composable
internal fun MainScreenDraftAutosaveEffect(
    editorController: com.lomo.app.feature.memo.MemoEditorController,
    dependencies: MainScreenDependencies,
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
            .collect { text -> dependencies.editorViewModel.saveDraft(text) }
    }
}

@Composable
internal fun MainScreenConflictHost(
    dependencies: MainScreenDependencies,
) {
    val syncStates by dependencies.conflictStateViewModel.syncStates.collectAsStateWithLifecycle()
    val workspaceRoot by dependencies.conflictStateViewModel.workspaceRoot.collectAsStateWithLifecycle()
    val conflictController =
        remember(dependencies.conflictViewModel) {
            SyncConflictDialogController(
                state = dependencies.conflictViewModel.state,
                onFileChoiceChanged = dependencies.conflictViewModel::setFileChoice,
                onAllChoicesChanged = dependencies.conflictViewModel::setAllChoices,
                onReviewItemChoiceChanged = dependencies.conflictViewModel::setReviewItemChoice,
                onAllReviewItemChoicesChanged = dependencies.conflictViewModel::setAllReviewItemChoices,
                onAcceptSuggestions = dependencies.conflictViewModel::acceptSuggestedChoices,
                onAutoResolveSafeConflicts = dependencies.conflictViewModel::autoResolveSafeConflicts,
                onToggleExpanded = dependencies.conflictViewModel::toggleExpandedFile,
                onApply = dependencies.conflictViewModel::applyResolution,
                onDismiss = dependencies.conflictViewModel::dismiss,
                onShowConflictDialog = dependencies.conflictViewModel::showConflictDialog,
                onShowReviewDialog = dependencies.conflictViewModel::showReviewDialog,
                onShowRemoteSession = dependencies.conflictViewModel::showRemoteConflictSession,
            )
        }
    com.lomo.app.feature.conflict.SyncConflictDialogHost(controller = conflictController)
    // Sync Inbox review only via provider state; remote conflicts via Rust poll.
    SyncConflictStateHost(
        syncStates = syncStates,
        providers = ReviewSyncProviders,
        controller = conflictController,
    )
    RemoteSyncConflictPollHost(
        workspaceRoot = workspaceRoot,
        onShowRemoteSession = conflictController.onShowRemoteSession,
        loadSession = dependencies.conflictStateViewModel::loadRemoteOpenSession,
    )
}

@Composable
internal fun MainScreenContentHost(
    screenState: MainScreenUiSnapshot,
    pagedUiMemos: LazyPagingItems<MemoUiModel>,
    hostState: MainScreenHostState,
    dependencies: MainScreenDependencies,
    unknownErrorMessage: String,
    isRefreshing: Boolean,
    onNavigateToSettings: () -> Unit,
    onNavigateToTrash: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToTag: (String) -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
    onNavigateToDailyReview: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onNavigateToTasks: () -> Unit,
    onNavigateToStatistics: () -> Unit,
    onNavigateToShare: (String, Long) -> Unit,
    lanShareEnabled: Boolean,
) {
    val allTags =
        remember(screenState.sidebarUiState.tags) {
            screenState.sidebarUiState.tags.map { it.name }.sorted().toImmutableList()
        }

        MainScreenInteractionBindings(
        dependencies = dependencies,
        editorController = hostState.editorController,
        directoryGuideController = hostState.directoryGuideController,
        scope = hostState.scope,
        snackbarHostState = hostState.snackbarHostState,
        unknownErrorMessage = unknownErrorMessage,
        shareCardShowTime = screenState.shareCardShowTime,
        shareCardShowSignature = screenState.shareCardShowSignature,
        shareCardSignatureText = screenState.shareCardSignatureText,
        customFontPath = screenState.customFontPath,
        dateFormat = screenState.dateFormat,
        timeFormat = screenState.timeFormat,
        quickSaveOnBackEnabled = screenState.quickSaveOnBackEnabled,
        memoActionAutoReorderEnabled = screenState.memoActionAutoReorderEnabled,
        memoActionOrder = screenState.memoActionOrder,
        inputToolbarToolOrder = screenState.inputToolbarToolOrder,
        availableTags = allTags,
        showInputHints = screenState.showInputHints,
        onNavigateToShare = onNavigateToShare,
        lanShareEnabled = lanShareEnabled,
    ) { showMenu, openEditor ->
        MainScreenNavigationContent(
            screenState = screenState,
            pagedUiMemos = pagedUiMemos,
            hostState = hostState,
            dependencies = dependencies,
            isRefreshing = isRefreshing,
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToTrash = onNavigateToTrash,
            onNavigateToSearch = onNavigateToSearch,
            onNavigateToTag = onNavigateToTag,
            onNavigateToImage = onNavigateToImage,
            onNavigateToDailyReview = onNavigateToDailyReview,
            onNavigateToGallery = onNavigateToGallery,
            onNavigateToTasks = onNavigateToTasks,
            onNavigateToStatistics = onNavigateToStatistics,
            onShowMemoMenu = showMenu,
            onOpenEditor = openEditor,
        )
    }

    MainDirectoryGuideHost(
        controller = hostState.directoryGuideController,
        actions =
            MainDirectoryGuideActions(
                onConfirmCreate = { type ->
                    dependencies.mainViewModel.createDefaultDirectories(
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
    pagedUiMemos: LazyPagingItems<MemoUiModel>,
    hostState: MainScreenHostState,
    dependencies: MainScreenDependencies,
    isRefreshing: Boolean,
    onNavigateToSettings: () -> Unit,
    onNavigateToTrash: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToTag: (String) -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
    onNavigateToDailyReview: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onNavigateToTasks: () -> Unit,
    onNavigateToStatistics: () -> Unit,
    onShowMemoMenu: (MemoMenuSelection) -> Unit,
    onOpenEditor: (Memo) -> Unit,
) {
    var isMemoFilterSheetVisible by rememberSaveable { mutableStateOf(false) }
    val clearMainFilters = rememberClearMainFiltersAction(dependencies)
    val onHeatmapDateLongPress = rememberMainScreenHeatmapLongPressAction(dependencies, hostState)
    val onScrollToTop = rememberMainScreenScrollToTopAction(hostState)
    val sessionSnapshot =
        rememberSaveable(stateSaver = mainListSessionSnapshotSaver) {
            mutableStateOf(MainListSessionSnapshot.Empty)
        }

    MainScreenSessionRestoreEffect(
        dependencies = dependencies,
        listState = hostState.listState,
        sessionSnapshot = sessionSnapshot,
    )

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
                screenState.pendingNewMemoCreationEvent == null,
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
        onNavigateToTasks = onNavigateToTasks,
        onNavigateToStatistics = onNavigateToStatistics,
        onClearMainFilters = clearMainFilters,
        onOpenMemoFilterPanel = { isMemoFilterSheetVisible = true },
        onOpenCreateMemo = {
            if (screenState.pendingNewMemoCreationEvent == null) {
                hostState.editorController.openForCreate(dependencies.editorViewModel.draftText.value)
            }
        },
        onRefreshMemos = dependencies.mainViewModel.refresh,
    ) { actions ->
        MainScreenNavigationRender(
            screenState = screenState,
            pagedUiMemos = pagedUiMemos,
            hostState = hostState,
            viewModel = dependencies.mainViewModel,
            actions = actions,
            isRefreshing = isRefreshing,
            isMemoFilterSheetVisible = isMemoFilterSheetVisible,
            onDismissMemoFilterSheet = { isMemoFilterSheetVisible = false },
            onHeatmapDateLongPress = onHeatmapDateLongPress,
            onScrollToTop = onScrollToTop,
            onShowMemoMenu = onShowMemoMenu,
            onReminderClick = dependencies.mainViewModel.markReminderDone,
            onOpenEditor = onOpenEditor,
            onSidebarTagReorder = dependencies.sidebarViewModel::updateTagOrder,
        )
    }
}

/**
 * Applies the saved main-list session once the mounted workspace publishes its location, then keeps
 * the durable snapshot in sync with the live session owner and viewport.
 *
 * Restore order is query and structural filter first — through the single session owner — and the
 * viewport anchor second, so the list never composes a restored position under a stale query.
 * A snapshot bound to a different workspace resets instead of showing the stale page.
 */
@Composable
internal fun MainScreenSessionRestoreEffect(
    dependencies: MainScreenDependencies,
    listState: LazyListState,
    sessionSnapshot: MutableState<MainListSessionSnapshot>,
) {
    val mount by dependencies.mainViewModel.mount.collectAsStateWithLifecycle()
    val workspacePath = mount.location?.raw
    val searchQuery by dependencies.mainViewModel.searchQuery.collectAsStateWithLifecycle()
    val memoListFilter by dependencies.mainViewModel.memoListFilter.collectAsStateWithLifecycle()
    var restoreSettled by remember { mutableStateOf(false) }

    LaunchedEffect(workspacePath) {
        val path = workspacePath ?: return@LaunchedEffect
        when (val action = resolveMainListSessionRestore(sessionSnapshot.value, path)) {
            is MainListSessionRestoreAction.Apply -> {
                dependencies.mainViewModel.restoreMainListSession(
                    query = action.snapshot.searchQuery,
                    filter = action.snapshot.filter,
                )
                if (action.snapshot.anchorIndex != 0 || action.snapshot.anchorOffset != 0) {
                    listState.scrollToItem(
                        action.snapshot.anchorIndex,
                        action.snapshot.anchorOffset,
                    )
                }
            }
            MainListSessionRestoreAction.Reset -> {
                listState.scrollToItem(0)
                sessionSnapshot.value =
                    MainListSessionSnapshot.Empty.copy(workspacePath = path)
            }
            MainListSessionRestoreAction.Wait -> Unit
        }
        restoreSettled = true
    }

    LaunchedEffect(restoreSettled, workspacePath, searchQuery, memoListFilter) {
        val path = workspacePath ?: return@LaunchedEffect
        if (!restoreSettled) return@LaunchedEffect
        val snapshot = sessionSnapshot.value
        if (snapshot.workspacePath == path &&
            (snapshot.searchQuery != searchQuery || snapshot.filter != memoListFilter)
        ) {
            sessionSnapshot.value =
                snapshot.copy(searchQuery = searchQuery, filter = memoListFilter)
        }
    }

    LaunchedEffect(restoreSettled, workspacePath, listState) {
        val path = workspacePath ?: return@LaunchedEffect
        if (!restoreSettled) return@LaunchedEffect
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.distinctUntilChanged()
            .collect { (index, offset) ->
                val snapshot = sessionSnapshot.value
                if (snapshot.workspacePath == path &&
                    (snapshot.anchorIndex != index || snapshot.anchorOffset != offset)
                ) {
                    sessionSnapshot.value =
                        snapshot.copy(anchorIndex = index, anchorOffset = offset)
                }
            }
    }
}

@Composable
private fun MainScreenFilterScrollEffect(
    searchQuery: String,
    memoListFilter: MemoListFilter,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    // Comparison baseline only — never persisted; it is seeded from the session the owner
    // publishes at composition, which is the restored session when one applied.
    var previousQuery by remember { mutableStateOf(searchQuery) }
    var previousMemoFilter by remember { mutableStateOf(memoListFilter) }

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
    dependencies: MainScreenDependencies,
): () -> Unit =
    remember(dependencies.mainViewModel) {
        {
            dependencies.mainViewModel.clearMemoFilter()
        }
    }

@Composable
private fun rememberMainScreenHeatmapLongPressAction(
    dependencies: MainScreenDependencies,
    hostState: MainScreenHostState,
): (LocalDate) -> Unit =
    remember(dependencies.mainViewModel, hostState) {
        { date ->
            dependencies.mainViewModel.filterMemosByDate(date)
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
