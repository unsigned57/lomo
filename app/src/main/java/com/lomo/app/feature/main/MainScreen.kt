package com.lomo.app.feature.main
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.lomo.app.R
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.memo.MemoEditorViewModel
import com.lomo.app.feature.memo.MemoInteractionHost
import com.lomo.app.feature.memo.rememberMemoEditorController
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoSortOption
import com.lomo.ui.component.navigation.SidebarDrawer
import com.lomo.ui.theme.MotionTokens
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * MainScreen with comprehensive audit improvements.
 *
 * 1. Performance:
 * ```
 *    - Uses @Immutable wrappers for Lists to prevent over-recomposition.
 *    - Implements derivedStateOf for scroll-dependent UI logic (FAB visibility).
 *    - Optimized LazyColumn with content keys.
 * ```
 * 2. Architecture:
 * ```
 *    - Separation of concerns: UI Actions encapsulated in a clean interface.
 *    - Logic for image saving is handled via ViewModel callbacks, keeping UI clean.
 * ```
 * 3. UI/UX:
 * ```
 *    - Material 3 Design implementation.
 *    - Fluid AnimatedContent for state transitions.
 *    - Physics-based animations for FAB and Lists.
 * ```
 */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun MainScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToTrash: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToTag: (String) -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
    onNavigateToDailyReview: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onNavigateToShare: (String, Long) -> Unit = { _, _ -> },
    viewModel: MainViewModel = hiltViewModel(),
    sidebarViewModel: SidebarViewModel = hiltViewModel(),
    editorViewModel: MemoEditorViewModel = hiltViewModel(),
    recordingViewModel: RecordingViewModel = hiltViewModel(),
    conflictViewModel: com.lomo.app.feature.conflict.SyncConflictViewModel = hiltViewModel(),
) {
    // Collect Flow state safely with Lifecycle awareness using collectAsStateWithLifecycle
    // to ensure flows are paused when the app is in the background.
    val uiMemos by viewModel.uiMemos.collectAsStateWithLifecycle()
    val searchQuery by sidebarViewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedTag by sidebarViewModel.selectedTag.collectAsStateWithLifecycle()
    val memoListFilter by viewModel.memoListFilter.collectAsStateWithLifecycle()
    val sidebarUiState by sidebarViewModel.sidebarUiState.collectAsStateWithLifecycle()
    val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()
    val dateFormat = appPreferences.dateFormat
    val timeFormat = appPreferences.timeFormat
    val showInputHints = appPreferences.showInputHints
    val doubleTapEditEnabled = appPreferences.doubleTapEditEnabled
    val freeTextCopyEnabled = appPreferences.freeTextCopyEnabled
    val quickSaveOnBackEnabled = appPreferences.quickSaveOnBackEnabled
    val shareCardShowTime = appPreferences.shareCardShowTime

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Manual pull-to-refresh is available for explicit data reload.
    val hasItems = uiMemos.isNotEmpty()

    // Host State
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    val unknownErrorMessage = stringResource(R.string.error_unknown)
    val listState =
        rememberSaveable(saver = androidx.compose.foundation.lazy.LazyListState.Saver) {
            androidx.compose.foundation.lazy
                .LazyListState()
        }
    val editorController = rememberMemoEditorController()

    // Debounced draft save: when in create mode, save input text to DataStore
    LaunchedEffect(editorController) {
        snapshotFlow {
            DraftAutosaveState(
                editingMemoId = editorController.editingMemo?.id,
                text = editorController.inputValue.text,
                isVisible = editorController.isVisible,
            )
        }.filter { state ->
            state.editingMemoId == null && state.isVisible
        }.map { state -> state.text }
            .debounce(500)
            .distinctUntilChanged()
            .collect { text ->
                editorViewModel.saveDraft(text)
            }
    }

    // Local UI State
    var isRefreshing by remember { mutableStateOf(false) }

    // Adaptive Layout
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val isExpanded = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val directoryGuideController = rememberMainDirectoryGuideController()

    // Track scroll to top for new memo insertions
    var pendingNewMemoScroll by remember { mutableStateOf(false) }

    // LaunchedEffect to scroll to top once the new memo is actually inserted in the data source
    LaunchedEffect(uiMemos.size) {
        if (pendingNewMemoScroll && uiMemos.isNotEmpty()) {
            pendingNewMemoScroll = false
            listState.animateScrollToItem(0)
        }
    }

    MainScreenTransientEffects(
        viewModel = viewModel,
        editorViewModel = editorViewModel,
        uiMemos = uiMemos,
        listState = listState,
        editorController = editorController,
        directoryGuideController = directoryGuideController,
        snackbarHostState = snackbarHostState,
        unknownErrorMessage = unknownErrorMessage,
    )

    val allTags = remember(sidebarUiState.tags) { sidebarUiState.tags.map { it.name }.sorted() }

    com.lomo.app.feature.conflict.SyncConflictDialogHost(conflictViewModel = conflictViewModel)

    LaunchedEffect(Unit) {
        viewModel.syncConflictEvent.collect { conflictSet ->
            conflictViewModel.showConflictDialog(conflictSet)
        }
    }

    MainScreenInteractionBindings(
        viewModel = viewModel,
        editorViewModel = editorViewModel,
        recordingViewModel = recordingViewModel,
        editorController = editorController,
        directoryGuideController = directoryGuideController,
        scope = scope,
        snackbarHostState = snackbarHostState,
        unknownErrorMessage = unknownErrorMessage,
        shareCardShowTime = shareCardShowTime,
        quickSaveOnBackEnabled = quickSaveOnBackEnabled,
        availableTags = allTags,
        showInputHints = showInputHints,
        onNavigateToShare = onNavigateToShare,
        onPendingNewMemoScroll = { pendingNewMemoScroll = true },
    ) { showMenu, openEditor ->
        var isMemoFilterSheetVisible by rememberSaveable { mutableStateOf(false) }

        // Track previous filter values to detect actual changes (not recomposition)
        var previousTag by rememberSaveable { mutableStateOf<String?>(null) }
        var previousQuery by rememberSaveable { mutableStateOf("") }
        var previousMemoFilter by remember { mutableStateOf(MemoListFilter()) }

        // Scroll to top ONLY when filter actually changes (user action)
        LaunchedEffect(selectedTag, searchQuery, memoListFilter) {
            val filterChanged =
                previousTag != selectedTag ||
                    previousQuery != searchQuery ||
                    previousMemoFilter != memoListFilter
            // Only scroll if this is a real user-initiated filter change
            // (not initial composition or navigation return)
            if (filterChanged && (previousTag != null || previousQuery.isNotEmpty() || previousMemoFilter.isActive)) {
                listState.scrollToItem(0)
            }
            previousTag = selectedTag
            previousQuery = searchQuery
            previousMemoFilter = memoListFilter
        }

        MainScreenNavigationActionHost(
            scope = scope,
            drawerState = drawerState,
            isExpanded = isExpanded,
            canCreateMemo = uiState is MainViewModel.MainScreenState.Ready,
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToTrash = onNavigateToTrash,
            onNavigateToSearch = onNavigateToSearch,
            onNavigateToTag = onNavigateToTag,
            onNavigateToImage = onNavigateToImage,
            onNavigateToDailyReview = onNavigateToDailyReview,
            onNavigateToGallery = onNavigateToGallery,
            onClearSidebarFilters = sidebarViewModel::clearFilters,
            onClearMainFilters = {
                sidebarViewModel.onTagSelected(null)
                viewModel.clearMemoDateRange()
            },
            onOpenMemoFilterPanel = { isMemoFilterSheetVisible = true },
            onOpenCreateMemo = { editorController.openForCreate(editorViewModel.draftText.value) },
            onRefreshMemos = viewModel::refresh,
            onRefreshingChange = { isRefreshing = it },
        ) { actions ->
            MainScreenRenderHost(
                isExpanded = isExpanded,
                drawerState = drawerState,
                sidebarUiState = sidebarUiState,
                actions = actions,
                snackbarHostState = snackbarHostState,
                scrollBehavior = scrollBehavior,
                selectedTag = selectedTag,
                searchQuery = searchQuery,
                memoListFilter = memoListFilter,
                isFilterActive = selectedTag != null || memoListFilter.hasDateRange,
                isMemoFilterSheetVisible = isMemoFilterSheetVisible,
                uiState = uiState,
                hasItems = hasItems,
                uiMemos = uiMemos,
                listState = listState,
                isRefreshing = isRefreshing,
                onTodoClick = { memo, index, checked -> viewModel.updateMemo(memo, index, checked) },
                dateFormat = dateFormat,
                timeFormat = timeFormat,
                onMemoDoubleClick = openEditor,
                doubleTapEditEnabled = doubleTapEditEnabled,
                freeTextCopyEnabled = freeTextCopyEnabled,
                onShowMemoMenu = showMenu,
                onMemoSortOptionSelected = viewModel::updateMemoSortOption,
                onMemoStartDateSelected = viewModel::updateMemoStartDate,
                onMemoEndDateSelected = viewModel::updateMemoEndDate,
                onClearMemoDateRange = viewModel::clearMemoDateRange,
                onResetMemoFilter = viewModel::clearMemoListFilter,
                onDismissMemoFilterSheet = { isMemoFilterSheetVisible = false },
                onHeatmapDateLongPress = { date ->
                    viewModel.filterMemosByDate(date)
                    if (!isExpanded) {
                        scope.launch { drawerState.close() }
                    }
                },
                onScrollToTop = {
                    scope.launch {
                        if (listState.firstVisibleItemIndex > 10) {
                            listState.scrollToItem(10)
                        }
                        listState.animateScrollToItem(0)
                    }
                },
            )
        }

        MainDirectoryGuideHost(
            controller = directoryGuideController,
            actions =
                MainDirectoryGuideActions(
                    onConfirmCreate = { type ->
                        viewModel.createDefaultDirectories(
                            forImage = type == DirectorySetupType.Image,
                            forVoice = type == DirectorySetupType.Voice,
                        )
                    },
                    onBeforeGoToSettings = editorController::close,
                    onGoToSettings = onNavigateToSettings,
                ),
        )
    }
}

private data class DraftAutosaveState(
    val editingMemoId: String?,
    val text: String,
    val isVisible: Boolean,
)

@Composable
private fun rememberInputHints(showInputHints: Boolean): List<String> {
    val hint1 = stringResource(R.string.input_hint_1)
    val hint2 = stringResource(R.string.input_hint_2)
    val hint3 = stringResource(R.string.input_hint_3)
    val hint4 = stringResource(R.string.input_hint_4)
    val hint5 = stringResource(R.string.input_hint_5)
    val hint6 = stringResource(R.string.input_hint_6)
    val hint7 = stringResource(R.string.input_hint_7)

    return remember(showInputHints, hint1, hint2, hint3, hint4, hint5, hint6, hint7) {
        if (!showInputHints) {
            emptyList()
        } else {
            listOf(hint1, hint2, hint3, hint4, hint5, hint6, hint7)
        }
    }
}

@Composable
private fun MainScreenTransientEffects(
    viewModel: MainViewModel,
    editorViewModel: MemoEditorViewModel,
    uiMemos: List<MemoUiModel>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    editorController: com.lomo.app.feature.memo.MemoEditorController,
    directoryGuideController: MainDirectoryGuideController,
    snackbarHostState: SnackbarHostState,
    unknownErrorMessage: String,
) {
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val editorErrorMessage by editorViewModel.errorMessage.collectAsStateWithLifecycle()
    val sharedContentEvents by viewModel.sharedContentEvents.collectAsStateWithLifecycle()
    val pendingSharedImageEvents by viewModel.pendingSharedImageEvents.collectAsStateWithLifecycle()
    val appActionEvents by viewModel.appActionEvents.collectAsStateWithLifecycle()
    val imageDirectory by viewModel.imageDirectory.collectAsStateWithLifecycle()
    val draftText by editorViewModel.draftText.collectAsStateWithLifecycle()

    MainScreenEventEffectsHost(
        sharedContentEvents = sharedContentEvents,
        appActionEvents = appActionEvents,
        pendingSharedImageEvents = pendingSharedImageEvents,
        imageDirectory = imageDirectory,
        errorMessage = errorMessage,
        editorErrorMessage = editorErrorMessage,
        snackbarHostState = snackbarHostState,
        unknownErrorMessage = unknownErrorMessage,
        onAppendMarkdown = editorController::appendMarkdownBlock,
        onAppendImageMarkdown = editorController::appendImageMarkdown,
        onEnsureEditorVisible = editorController::ensureVisible,
        onOpenCreateMemo = { editorController.openForCreate(draftText) },
        onOpenEditMemo = editorController::openForEdit,
        onFocusMemoInList = { memoId ->
            val index = uiMemos.indexOfFirst { it.memo.id == memoId }
            if (index >= 0) {
                listState.animateScrollToItem(index)
                true
            } else {
                false
            }
        },
        onResolveMemoById = viewModel::resolveMemoById,
        onSaveImage = { uri, onResult -> editorViewModel.saveImage(uri = uri, onResult = onResult) },
        onRequireImageDirectory = directoryGuideController::requestImage,
        onConsumeSharedContentEvent = viewModel::consumeSharedContentEvent,
        onConsumeAppActionEvent = viewModel::consumeAppActionEvent,
        onConsumePendingSharedImageEvent = viewModel::consumePendingSharedImageEvent,
        onClearMainError = viewModel::clearError,
        onClearEditorError = editorViewModel::clearError,
    )
}

@Composable
private fun MainScreenInteractionBindings(
    viewModel: MainViewModel,
    editorViewModel: MemoEditorViewModel,
    recordingViewModel: RecordingViewModel,
    editorController: com.lomo.app.feature.memo.MemoEditorController,
    directoryGuideController: MainDirectoryGuideController,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbarHostState: SnackbarHostState,
    unknownErrorMessage: String,
    shareCardShowTime: Boolean,
    quickSaveOnBackEnabled: Boolean,
    availableTags: List<String>,
    showInputHints: Boolean,
    onNavigateToShare: (String, Long) -> Unit,
    onPendingNewMemoScroll: () -> Unit,
    content: @Composable ((com.lomo.ui.component.menu.MemoMenuState) -> Unit, (com.lomo.domain.model.Memo) -> Unit) -> Unit,
) {
    val activeDayCount by viewModel.activeDayCount.collectAsStateWithLifecycle()
    val gitSyncEnabled by viewModel.gitSyncEnabled.collectAsStateWithLifecycle()
    val versionHistoryState by viewModel.versionHistoryState.collectAsStateWithLifecycle()
    val imageDirectory by viewModel.imageDirectory.collectAsStateWithLifecycle()
    val voiceDirectory by viewModel.voiceDirectory.collectAsStateWithLifecycle()
    val inputHints = rememberInputHints(showInputHints = showInputHints)

    MemoInteractionHost(
        shareCardShowTime = shareCardShowTime,
        activeDayCount = activeDayCount,
        imageDirectory = imageDirectory,
        controller = editorController,
        quickSaveOnBackEnabled = quickSaveOnBackEnabled,
        onDeleteMemo = viewModel::deleteMemo,
        onUpdateMemo = editorViewModel::updateMemo,
        onCreateMemo = { contentText ->
            editorViewModel.createMemo(contentText) {
                onPendingNewMemoScroll()
            }
        },
        onSaveImage = editorViewModel::saveImage,
        onLanShare = onNavigateToShare,
        onDismiss = editorViewModel::discardInputs,
        onImageDirectoryMissing = directoryGuideController::requestImage,
        onCameraCaptureError = { error ->
            scope.launch {
                snackbarHostState.showSnackbar(error.message ?: unknownErrorMessage)
            }
        },
        availableTags = availableTags,
        isRecordingFlow = recordingViewModel.isRecording,
        recordingDurationFlow = recordingViewModel.recordingDuration,
        recordingAmplitudeFlow = recordingViewModel.recordingAmplitude,
        onStartRecording = {
            if (voiceDirectory == null) {
                directoryGuideController.requestVoice()
            } else {
                recordingViewModel.startRecording()
            }
        },
        onCancelRecording = recordingViewModel::cancelRecording,
        onStopRecording = {
            recordingViewModel.stopRecording { markdown ->
                editorController.appendMarkdownBlock(markdown)
            }
        },
        hints = inputHints,
        onVersionHistory = { state ->
            val memo = state.memo as? com.lomo.domain.model.Memo
            if (memo != null) {
                viewModel.loadVersionHistory(memo)
            }
        },
        onTogglePin = viewModel::setMemoPinned,
        showVersionHistory = gitSyncEnabled,
    ) { showMenu, openEditor ->
        content(showMenu, openEditor)
        VersionHistoryOverlay(
            state = versionHistoryState,
            onDismiss = viewModel::dismissVersionHistory,
            onRestore = { memo, version -> viewModel.restoreVersion(memo, version) },
        )
    }
}

// Refactor: Sub-components extracted to separate files:
// - MainTopBar -> MainScreenTopBar.kt
// - MainFab -> MainScreenFab.kt
// - MainEmptyState -> MainScreenEmptyState.kt
// - MemoListContent, MemoItemContent -> MemoListContent.kt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreenRenderHost(
    isExpanded: Boolean,
    drawerState: androidx.compose.material3.DrawerState,
    sidebarUiState: SidebarViewModel.SidebarUiState,
    actions: MainScreenActions,
    snackbarHostState: SnackbarHostState,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    selectedTag: String?,
    searchQuery: String,
    memoListFilter: MemoListFilter,
    isFilterActive: Boolean,
    isMemoFilterSheetVisible: Boolean,
    uiState: MainViewModel.MainScreenState,
    hasItems: Boolean,
    uiMemos: List<MemoUiModel>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isRefreshing: Boolean,
    onTodoClick: (com.lomo.domain.model.Memo, Int, Boolean) -> Unit,
    dateFormat: String,
    timeFormat: String,
    onMemoDoubleClick: (com.lomo.domain.model.Memo) -> Unit,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    onShowMemoMenu: (com.lomo.ui.component.menu.MemoMenuState) -> Unit,
    onMemoSortOptionSelected: (MemoSortOption) -> Unit,
    onMemoStartDateSelected: (LocalDate?) -> Unit,
    onMemoEndDateSelected: (LocalDate?) -> Unit,
    onClearMemoDateRange: () -> Unit,
    onResetMemoFilter: () -> Unit,
    onDismissMemoFilterSheet: () -> Unit,
    onHeatmapDateLongPress: (LocalDate) -> Unit,
    onScrollToTop: () -> Unit,
) {
    val sidebarContent: @Composable () -> Unit = {
        SidebarDrawer(
            username = "Lomo",
            stats = sidebarUiState.stats,
            memoCountByDate = sidebarUiState.memoCountByDate,
            tags = sidebarUiState.tags,
            onMemoClick = actions.onSidebarMemoClick,
            onTagClick = actions.onSidebarTagClick,
            onSettingsClick = actions.onSettings,
            onTrashClick = actions.onTrash,
            onDailyReviewClick = actions.onDailyReviewClick,
            onGalleryClick = actions.onGalleryClick,
            onHeatmapDateLongPress = onHeatmapDateLongPress,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    val screenContent: @Composable () -> Unit = {
        val isFabVisible by remember {
            androidx.compose.runtime.derivedStateOf { scrollBehavior.state.collapsedFraction < 0.9f }
        }
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            contentWindowInsets =
                WindowInsets.displayCutout
                    .union(WindowInsets.systemBars)
                    .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            topBar = {
                MainTopBar(
                    title = if (selectedTag != null) "#$selectedTag" else "Lomo",
                    scrollBehavior = scrollBehavior,
                    onMenu = actions.onMenuOpen,
                    onSearch = actions.onSearch,
                    onFilter = actions.onOpenMemoFilterPanel,
                    onClearFilter = actions.onClearFilter,
                    isFilterActive = isFilterActive,
                    showNavigationIcon = !isExpanded,
                )
            },
            floatingActionButton = {
                MainFab(
                    isVisible = isFabVisible,
                    onClick = actions.onFabClick,
                    modifier = Modifier.offset(y = (-16).dp),
                    onLongClick = onScrollToTop,
                )
            },
            floatingActionButtonPosition = FabPosition.Center,
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                AnimatedContent(
                    targetState = uiState,
                    transitionSpec = {
                        (
                            fadeIn(
                                animationSpec =
                                    tween(
                                        durationMillis = MotionTokens.DurationLong2,
                                        easing = MotionTokens.EasingStandard,
                                    ),
                            ) +
                                scaleIn(
                                    initialScale = 0.92f,
                                    animationSpec =
                                        tween(
                                            durationMillis = MotionTokens.DurationLong2,
                                            easing = MotionTokens.EasingEmphasizedDecelerate,
                                        ),
                                )
                        ) togetherWith
                            fadeOut(
                                animationSpec =
                                    tween(
                                        durationMillis = MotionTokens.DurationLong2,
                                        easing = MotionTokens.EasingStandard,
                                    ),
                            )
                    },
                    label = "MainScreenStateTransition",
                ) { state ->
                    when (state) {
                        is MainViewModel.MainScreenState.Loading -> {
                            com.lomo.ui.component.common.MemoListSkeleton(
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        is MainViewModel.MainScreenState.NoDirectory -> {
                            MainEmptyState(
                                searchQuery = searchQuery,
                                selectedTag = selectedTag,
                                hasDirectory = false,
                                onSettings = actions.onSettings,
                            )
                        }

                        is MainViewModel.MainScreenState.Ready -> {
                            MainReadyStateEnterContainer {
                                Crossfade(
                                    targetState = hasItems,
                                    animationSpec =
                                        tween(
                                            durationMillis = MotionTokens.DurationMedium2,
                                            easing = MotionTokens.EasingStandard,
                                        ),
                                    label = "ReadyContentCrossfade",
                                ) { showList ->
                                    if (!showList) {
                                        MainEmptyState(
                                            searchQuery = searchQuery,
                                            selectedTag = selectedTag,
                                            hasDirectory = true,
                                            onSettings = actions.onSettings,
                                        )
                                    } else {
                                        MemoListContent(
                                            memos = uiMemos,
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
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isMemoFilterSheetVisible) {
            MainMemoFilterSheet(
                filter = memoListFilter,
                onSortOptionSelected = onMemoSortOptionSelected,
                onStartDateSelected = onMemoStartDateSelected,
                onEndDateSelected = onMemoEndDateSelected,
                onClearDateRange = onClearMemoDateRange,
                onReset = onResetMemoFilter,
                onDismiss = onDismissMemoFilterSheet,
            )
        }
    }

    if (isExpanded) {
        PermanentNavigationDrawer(
            drawerContent = {
                PermanentDrawerSheet(
                    modifier = Modifier.width(300.dp),
                ) {
                    sidebarContent()
                }
            },
            content = screenContent,
        )
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.8f)) {
                    sidebarContent()
                }
            },
            content = screenContent,
        )
    }
}

@Composable
private fun MainReadyStateEnterContainer(content: @Composable () -> Unit) {
    val visibleState =
        remember {
            MutableTransitionState(false).apply {
                targetState = true
            }
        }
    AnimatedVisibility(
        visibleState = visibleState,
        enter = MotionTokens.enterContent,
        exit = ExitTransition.None,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

@Composable
private fun VersionHistoryOverlay(
    state: MainVersionHistoryState,
    onDismiss: () -> Unit,
    onRestore: (com.lomo.domain.model.Memo, com.lomo.domain.model.MemoVersion) -> Unit,
) {
    when (state) {
        is MainVersionHistoryState.Loading -> {
            com.lomo.app.feature.memo.MemoVersionHistorySheet(
                versions = emptyList(),
                isLoading = true,
                onRestore = {},
                onDismiss = onDismiss,
            )
        }

        is MainVersionHistoryState.Loaded -> {
            com.lomo.app.feature.memo.MemoVersionHistorySheet(
                versions = state.versions,
                isLoading = false,
                onRestore = { version -> onRestore(state.memo, version) },
                onDismiss = onDismiss,
            )
        }

        MainVersionHistoryState.Hidden -> {
            Unit
        }
    }
}
