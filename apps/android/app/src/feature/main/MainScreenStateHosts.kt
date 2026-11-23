package com.lomo.app.feature.main

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import com.lomo.app.R
import com.lomo.app.feature.conflict.RemoteSyncConflictPollHost
import com.lomo.app.feature.conflict.ReviewSyncProviders
import com.lomo.app.feature.conflict.SyncConflictDialogController
import com.lomo.app.feature.conflict.SyncConflictStateHost
import com.lomo.app.feature.conflict.SyncConflictStateHostBindings
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.memo.MemoEditorHostBindings
import com.lomo.app.feature.memo.MemoMenuSelection
import com.lomo.app.feature.memo.rememberMemoEditorController
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.StateFlow
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
    sidebarBindings: MainSidebarBindings,
    mainFlows: MainUiSnapshotFlows,
): MainScreenUiSnapshot {
    val searchQuery by sidebarBindings.searchQuery.collectAsStateWithLifecycle()
    val memoListFilter by mainFlows.memoListFilter.collectAsStateWithLifecycle()
    val sidebarUiState by sidebarBindings.sidebarUiState.collectAsStateWithLifecycle()
    val appPreferences by mainFlows.appPreferences.collectAsStateWithLifecycle()
    val uiState by mainFlows.uiState.collectAsStateWithLifecycle()
    val pendingNewMemoCreationEvents by mainFlows.pendingNewMemoCreationEvents.collectAsStateWithLifecycle()
    val pendingNewMemoCreationEvent = pendingNewMemoCreationEvents.lastOrNull()

    return MainScreenUiSnapshot(
        searchQuery = searchQuery,
        memoListFilter = memoListFilter,
        sidebarUiState = sidebarUiState,
        calendarHeatmapThresholds = appPreferences.calendarHeatmapThresholds,
        showInputHints = appPreferences.showInputHints,
        doubleTapEditEnabled = appPreferences.doubleTapEditEnabled,
        freeTextCopyEnabled = appPreferences.freeTextCopyEnabled,
        autoOpenInputOnForeground = appPreferences.autoOpenInputOnForeground,
        inputToolbarToolOrder = appPreferences.inputToolbarToolOrder,
        quickSaveOnBackEnabled = appPreferences.quickSaveOnBackEnabled,
        scrollbarEnabled = appPreferences.scrollbarEnabled,
        quickBallConfig = appPreferences.quickBallConfig,
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
    val placement = resolveMainSidebarPlacement(currentWindowAdaptiveInfoV2().windowSizeClass)
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val dockedPaneShown = rememberSaveable { mutableStateOf(true) }
    val sidebar =
        remember(placement, drawerState, dockedPaneShown) {
            MainSidebarState(placement = placement, drawerState = drawerState, dockedPaneShown = dockedPaneShown)
        }
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
    val directoryGuideController = rememberMainDirectoryGuideController()

    return remember(
        sidebar,
        scope,
        snackbarHostState,
        scrollBehavior,
        listState,
        editorController,
        directoryGuideController,
    ) {
        MainScreenHostState(
            sidebar = sidebar,
            scope = scope,
            snackbarHostState = snackbarHostState,
            scrollBehavior = scrollBehavior,
            listState = listState,
            editorController = editorController,
            directoryGuideController = directoryGuideController,
        )
    }
}

@OptIn(FlowPreview::class)
@Composable
internal fun MainScreenDraftAutosaveEffect(
    editorController: com.lomo.app.feature.memo.MemoEditorController,
    onSaveDraft: (String) -> Unit,
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
            .collect { text -> onSaveDraft(text) }
    }
}

@Composable
internal fun MainScreenConflictHost(
    conflictController: SyncConflictDialogController,
    stateBindings: SyncConflictStateHostBindings,
) {
    val syncStates by stateBindings.syncStates.collectAsStateWithLifecycle()
    val workspaceRoot by stateBindings.workspaceRoot.collectAsStateWithLifecycle()
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
        loadSession = stateBindings.loadRemoteOpenSession,
    )
}

@Composable
internal fun MainScreenContentHost(
    screenState: MainScreenUiSnapshot,
    pagedUiMemos: LazyPagingItems<MemoUiModel>,
    hostState: MainScreenHostState,
    contentBindings: MainContentHostBindings,
    sidebarBindings: MainSidebarBindings,
    editorBindings: MemoEditorHostBindings,
    recordingBindings: MainRecordingBindings,
    unknownErrorMessage: String,
    isRefreshing: Boolean,
    onNavigateToSettings: () -> Unit,
    onNavigateToTrash: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToTag: (String) -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
    onNavigateToDailyReview: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onNavigateToStatistics: () -> Unit,
    onNavigateToShare: (String, Long) -> Unit,
    lanShareEnabled: Boolean,
    expandCoordinator: MemoListExpandCoordinator? = null,
) {
    val allTags =
        remember(screenState.sidebarUiState.tags) {
            screenState.sidebarUiState.tags.map { it.name }.sorted().toImmutableList()
        }

    MainScreenInteractionBindings(
        versionMediaFlows = contentBindings.versionMediaFlows,
        memoInteractions = contentBindings.memoInteractions,
        editorBindings = editorBindings,
        recordingBindings = recordingBindings,
        editorController = hostState.editorController,
        directoryGuideController = hostState.directoryGuideController,
        scope = hostState.scope,
        snackbarHostState = hostState.snackbarHostState,
        unknownErrorMessage = unknownErrorMessage,
        shareCardShowTime = screenState.shareCardShowTime,
        shareCardShowSignature = screenState.shareCardShowSignature,
        shareCardSignatureText = screenState.shareCardSignatureText,
        customFontPath = screenState.customFontPath,
        quickSaveOnBackEnabled = screenState.quickSaveOnBackEnabled,
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
            contentBindings = contentBindings,
            onSidebarTagReorder = sidebarBindings.onUpdateTagOrder,
            draftTextFlow = editorBindings.flows.draftText,
            isRecordingFlow = recordingBindings.flows.isRecording,
            isRefreshing = isRefreshing,
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToTrash = onNavigateToTrash,
            onNavigateToSearch = onNavigateToSearch,
            onNavigateToTag = onNavigateToTag,
            onNavigateToImage = onNavigateToImage,
            onNavigateToDailyReview = onNavigateToDailyReview,
            onNavigateToGallery = onNavigateToGallery,
            onNavigateToStatistics = onNavigateToStatistics,
            onShowMemoMenu = showMenu,
            onOpenEditor = openEditor,
            expandCoordinator = expandCoordinator,
        )
    }

    val directoryGuideActions =
        remember(contentBindings, hostState.editorController, onNavigateToSettings) {
            MainDirectoryGuideActions(
                onConfirmCreate = { type ->
                    contentBindings.navigationActions.onCreateDefaultDirectories(
                        type == DirectorySetupType.Image,
                        type == DirectorySetupType.Voice,
                    )
                },
                onBeforeGoToSettings = hostState.editorController::close,
                onGoToSettings = onNavigateToSettings,
            )
        }
    MainDirectoryGuideHost(
        controller = hostState.directoryGuideController,
        actions = directoryGuideActions,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreenNavigationContent(
    screenState: MainScreenUiSnapshot,
    pagedUiMemos: LazyPagingItems<MemoUiModel>,
    hostState: MainScreenHostState,
    contentBindings: MainContentHostBindings,
    onSidebarTagReorder: (List<String>) -> Unit,
    draftTextFlow: StateFlow<String>,
    isRecordingFlow: StateFlow<Boolean>,
    isRefreshing: Boolean,
    onNavigateToSettings: () -> Unit,
    onNavigateToTrash: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToTag: (String) -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
    onNavigateToDailyReview: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onNavigateToStatistics: () -> Unit,
    onShowMemoMenu: (MemoMenuSelection) -> Unit,
    onOpenEditor: (Memo) -> Unit,
    expandCoordinator: MemoListExpandCoordinator? = null,
) {
    var isMemoFilterSheetVisible by rememberSaveable { mutableStateOf(false) }
    val onHeatmapDateLongPress =
        rememberMainScreenHeatmapLongPressAction(
            onFilterMemosByDate = contentBindings.navigationActions.onFilterMemosByDate,
            hostState = hostState,
        )
    val onScrollToTop = rememberMainScreenScrollToTopAction(hostState)
    val isWorkspaceReady = screenState.uiState is MainViewModel.MainScreenState.Ready
    val navigationCallbacks =
        rememberMainNavigationCallbacks(
            hostState = hostState,
            contentBindings = contentBindings,
            draftTextFlow = draftTextFlow,
            isRecordingFlow = isRecordingFlow,
            isWorkspaceReady = isWorkspaceReady,
            onNavigateToSettings = onNavigateToSettings,
            onOpenMemoFilterSheet = remember { { isMemoFilterSheetVisible = true } },
        )
    MainScreenSessionRestoreEffect(
        sessionRestore = contentBindings.sessionRestore,
        listState = hostState.listState,
    )

    MainScreenFilterScrollEffect(
        searchQuery = screenState.searchQuery,
        memoListFilter = screenState.memoListFilter,
        listState = hostState.listState,
    )

    MainScreenNavigationActionHost(
        scope = hostState.scope,
        sidebar = hostState.sidebar,
        canCreateMemo =
        isWorkspaceReady &&
            screenState.pendingNewMemoCreationEvent == null,
        onCreateMemoUnavailable = navigationCallbacks.onCreateMemoUnavailable,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToTrash = onNavigateToTrash,
        onNavigateToSearch = onNavigateToSearch,
        onNavigateToTag = onNavigateToTag,
        onNavigateToImage = onNavigateToImage,
        onNavigateToDailyReview = onNavigateToDailyReview,
        onNavigateToGallery = onNavigateToGallery,
        onNavigateToStatistics = onNavigateToStatistics,
        onClearMainFilters = contentBindings.navigationActions.onClearMemoFilter,
        onOpenMemoFilterPanel = navigationCallbacks.onOpenMemoFilterPanel,
        onOpenCreateMemo = navigationCallbacks.onOpenCreateMemo,
        onOpenLongText = navigationCallbacks.onOpenLongText,
        onStartRecording = navigationCallbacks.onStartRecording,
        onRefreshMemos = contentBindings.navigationActions.onRefreshMemos,
    ) { actions ->
        MainScreenNavigationRender(
            screenState = screenState,
            pagedUiMemos = pagedUiMemos,
            hostState = hostState,
            listBindings = contentBindings.listRender,
            actions = actions,
            isRefreshing = isRefreshing,
            isMemoFilterSheetVisible = isMemoFilterSheetVisible,
            onDismissMemoFilterSheet = remember { { isMemoFilterSheetVisible = false } },
            onHeatmapDateLongPress = onHeatmapDateLongPress,
            expandCoordinator = expandCoordinator,
            fab = {
                val ballVisible = !hostState.editorController.isVisible &&
                    !isMemoFilterSheetVisible && !hostState.sidebar.coversFeed
                MainQuickBall(
                    visible = ballVisible,
                    config = screenState.quickBallConfig,
                    actions = actions,
                    onScrollToTop = onScrollToTop,
                )
            },
            onShowMemoMenu = onShowMemoMenu,
            onReminderClick = contentBindings.navigationActions.onMarkReminderDone,
            onOpenEditor = onOpenEditor,
            onSidebarTagReorder = onSidebarTagReorder,
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
internal fun MainScreenSessionRestoreEffect(sessionRestore: MainSessionRestoreBindings, listState: LazyListState) {
    // This effect is the only reader and writer of the durable snapshot, so it owns the state.
    val sessionSnapshot =
        rememberSaveable(stateSaver = mainListSessionSnapshotSaver) {
            mutableStateOf(MainListSessionSnapshot.Empty)
        }
    val mount by sessionRestore.mount.collectAsStateWithLifecycle()
    val workspacePath = mount.location?.raw
    val searchQuery by sessionRestore.searchQuery.collectAsStateWithLifecycle()
    val memoListFilter by sessionRestore.memoListFilter.collectAsStateWithLifecycle()
    var restoreSettled by remember { mutableStateOf(false) }

    LaunchedEffect(workspacePath) {
        val path = workspacePath ?: return@LaunchedEffect
        when (val action = resolveMainListSessionRestore(sessionSnapshot.value, path)) {
            is MainListSessionRestoreAction.Apply -> {
                sessionRestore.onRestoreMainListSession(
                    action.snapshot.searchQuery,
                    action.snapshot.filter,
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
private fun rememberMainScreenHeatmapLongPressAction(
    onFilterMemosByDate: (LocalDate) -> Unit,
    hostState: MainScreenHostState,
): (LocalDate) -> Unit = remember(onFilterMemosByDate, hostState) {
    { date ->
        onFilterMemosByDate(date)
        hostState.scope.launch { hostState.sidebar.closeOverlay() }
    }
}

@Composable
private fun rememberMainScreenScrollToTopAction(hostState: MainScreenHostState): () -> Unit = remember(hostState) {
    {
        hostState.scope.launch {
            if (hostState.listState.firstVisibleItemIndex > MAIN_SCREEN_LIST_SCROLL_SETTLE_INDEX) {
                hostState.listState.scrollToItem(MAIN_SCREEN_LIST_SCROLL_SETTLE_INDEX)
            }
            hostState.listState.animateScrollToItem(0)
        }
    }
}

private class MainNavigationCallbacks(
    val onCreateMemoUnavailable: () -> Unit,
    val onOpenMemoFilterPanel: () -> Unit,
    val onOpenCreateMemo: () -> Unit,
    val onOpenLongText: () -> Unit,
    val onStartRecording: () -> Unit,
)

@Composable
private fun rememberMainNavigationCallbacks(
    hostState: MainScreenHostState,
    contentBindings: MainContentHostBindings,
    draftTextFlow: StateFlow<String>,
    isRecordingFlow: StateFlow<Boolean>,
    isWorkspaceReady: Boolean,
    onNavigateToSettings: () -> Unit,
    onOpenMemoFilterSheet: () -> Unit,
): MainNavigationCallbacks {
    val busyMessage = stringResource(R.string.quick_ball_busy)
    val recordingFailureMessage = stringResource(R.string.quick_ball_record_failed)
    return remember(
        hostState,
        contentBindings,
        draftTextFlow,
        isRecordingFlow,
        isWorkspaceReady,
        busyMessage,
        recordingFailureMessage,
        onNavigateToSettings,
        onOpenMemoFilterSheet,
    ) {
        MainNavigationCallbacks(
            onCreateMemoUnavailable = {
                if (!isWorkspaceReady) {
                    onNavigateToSettings()
                } else {
                    hostState.scope.launch { hostState.snackbarHostState.showSnackbar(busyMessage) }
                }
            },
            onOpenMemoFilterPanel = onOpenMemoFilterSheet,
            onOpenCreateMemo = {
                hostState.editorController.openForCreate(draftTextFlow.value)
            },
            onOpenLongText = {
                hostState.editorController.openForCreate(draftTextFlow.value)
                hostState.editorController.setExpanded(true)
            },
            onStartRecording = {
                if (isRecordingFlow.value) {
                    hostState.editorController.ensureVisible()
                } else {
                    contentBindings.navigationActions.onRequestQuickBallRecording(recordingFailureMessage)
                }
            },
        )
    }
}
