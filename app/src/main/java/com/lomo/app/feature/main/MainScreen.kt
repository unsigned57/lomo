package com.lomo.app.feature.main
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.lomo.app.R
import com.lomo.app.feature.memo.MemoEditorViewModel
import com.lomo.app.feature.memo.MemoInteractionHost
import com.lomo.app.feature.memo.rememberMemoEditorController
import com.lomo.ui.component.navigation.SidebarDrawer
import com.lomo.ui.theme.MotionTokens
import kotlinx.coroutines.launch

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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToTrash: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToTag: (String) -> Unit,
    onNavigateToImage: (String) -> Unit,
    onNavigateToDailyReview: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onNavigateToShare: (String, Long) -> Unit = { _, _ -> },
    viewModel: MainViewModel = hiltViewModel(),
    sidebarViewModel: SidebarViewModel = hiltViewModel(),
    editorViewModel: MemoEditorViewModel = hiltViewModel(),
    recordingViewModel: RecordingViewModel = hiltViewModel(),
) {
    // Collect Flow state safely with Lifecycle awareness using collectAsStateWithLifecycle
    // to ensure flows are paused when the app is in the background.
    val uiMemos by viewModel.uiMemos.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val searchQuery by sidebarViewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedTag by sidebarViewModel.selectedTag.collectAsStateWithLifecycle()
    val sidebarUiState by sidebarViewModel.sidebarUiState.collectAsStateWithLifecycle()
    val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()
    val editorErrorMessage by editorViewModel.errorMessage.collectAsStateWithLifecycle()
    val dateFormat = appPreferences.dateFormat
    val timeFormat = appPreferences.timeFormat
    val showInputHints = appPreferences.showInputHints
    val doubleTapEditEnabled = appPreferences.doubleTapEditEnabled
    val shareCardStyle = appPreferences.shareCardStyle.value
    val shareCardShowTime = appPreferences.shareCardShowTime
    val activeDayCount by viewModel.activeDayCount.collectAsStateWithLifecycle()
    val gitSyncEnabled by viewModel.gitSyncEnabled.collectAsStateWithLifecycle()
    val versionHistoryState by viewModel.versionHistoryState.collectAsStateWithLifecycle()
    val sharedContentEvents by viewModel.sharedContentEvents.collectAsStateWithLifecycle()
    val pendingSharedImageEvents by viewModel.pendingSharedImageEvents.collectAsStateWithLifecycle()
    val appActionEvents by viewModel.appActionEvents.collectAsStateWithLifecycle()

    // Recording State (from RecordingViewModel)
    val isRecording by recordingViewModel.isRecording.collectAsStateWithLifecycle()
    val recordingDuration by recordingViewModel.recordingDuration.collectAsStateWithLifecycle()
    val recordingAmplitude by recordingViewModel.recordingAmplitude.collectAsStateWithLifecycle()

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val imageDir by viewModel.imageDirectory.collectAsStateWithLifecycle()
    val voiceDir by viewModel.voiceDirectory.collectAsStateWithLifecycle()

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

    MainScreenEventEffectsHost(
        sharedContentEvents = sharedContentEvents,
        appActionEvents = appActionEvents,
        pendingSharedImageEvents = pendingSharedImageEvents,
        imageDirectory = imageDir,
        errorMessage = errorMessage,
        editorErrorMessage = editorErrorMessage,
        snackbarHostState = snackbarHostState,
        unknownErrorMessage = unknownErrorMessage,
        onAppendMarkdown = editorController::appendMarkdownBlock,
        onAppendImageMarkdown = editorController::appendImageMarkdown,
        onEnsureEditorVisible = editorController::ensureVisible,
        onOpenCreateMemo = editorController::openForCreate,
        onOpenEditMemo = editorController::openForEdit,
        onResolveMemoById = viewModel::resolveMemoById,
        onSaveImage = { uri, onResult -> editorViewModel.saveImage(uri = uri, onResult = onResult) },
        onRequireImageDirectory = directoryGuideController::requestImage,
        onConsumeSharedContentEvent = viewModel::consumeSharedContentEvent,
        onConsumeAppActionEvent = viewModel::consumeAppActionEvent,
        onConsumePendingSharedImageEvent = viewModel::consumePendingSharedImageEvent,
        onClearMainError = viewModel::clearError,
        onClearEditorError = editorViewModel::clearError,
    )

    val allTags = remember(sidebarUiState.tags) { sidebarUiState.tags.map { it.name }.sorted() }

    MemoInteractionHost(
        shareCardStyle = shareCardStyle,
        shareCardShowTime = shareCardShowTime,
        activeDayCount = activeDayCount,
        imageDirectory = imageDir,
        controller = editorController,
        onDeleteMemo = viewModel::deleteMemo,
        onUpdateMemo = editorViewModel::updateMemo,
        onCreateMemo = { content ->
            editorViewModel.createMemo(content) {
                pendingNewMemoScroll = true
            }
        },
        onSaveImage = editorViewModel::saveImage,
        onLanShare = onNavigateToShare,
        onDismiss = editorViewModel::discardInputs,
        onImageDirectoryMissing = directoryGuideController::requestImage,
        onCameraCaptureError = { error ->
            scope.launch {
                snackbarHostState.showSnackbar(
                    error.message ?: unknownErrorMessage,
                )
            }
        },
        availableTags = allTags,
        isRecording = isRecording,
        recordingDuration = recordingDuration,
        recordingAmplitude = recordingAmplitude,
        onStartRecording = {
            if (voiceDir == null) {
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
        hints =
            if (showInputHints) {
                listOf(
                    stringResource(R.string.input_hint_1),
                    stringResource(R.string.input_hint_2),
                    stringResource(R.string.input_hint_3),
                    stringResource(R.string.input_hint_4),
                )
            } else {
                emptyList()
            },
        onVersionHistory = { state ->
            val memo = state.memo as? com.lomo.domain.model.Memo
            if (memo != null) {
                viewModel.loadVersionHistory(memo)
            }
        },
        showVersionHistory = gitSyncEnabled,
    ) { showMenu, openEditor ->

        // Track previous filter values to detect actual changes (not recomposition)
        var previousTag by rememberSaveable { mutableStateOf<String?>(null) }
        var previousQuery by rememberSaveable { mutableStateOf("") }

        // Scroll to top ONLY when filter actually changes (user action)
        LaunchedEffect(selectedTag, searchQuery) {
            val filterChanged = previousTag != selectedTag || previousQuery != searchQuery
            // Only scroll if this is a real user-initiated filter change
            // (not initial composition or navigation return)
            if (filterChanged && (previousTag != null || previousQuery.isNotEmpty())) {
                listState.scrollToItem(0)
            }
            previousTag = selectedTag
            previousQuery = searchQuery
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
            onClearSelectedTag = { sidebarViewModel.onTagSelected(null) },
            onOpenCreateMemo = editorController::openForCreate,
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
                uiState = uiState,
                hasItems = hasItems,
                uiMemos = uiMemos,
                listState = listState,
                isRefreshing = isRefreshing,
                onVisibleMemoIdsChanged = viewModel::updateVisibleMemoIds,
                onTodoClick = { memo, index, checked -> viewModel.updateMemo(memo, index, checked) },
                dateFormat = dateFormat,
                timeFormat = timeFormat,
                onMemoDoubleClick = openEditor,
                doubleTapEditEnabled = doubleTapEditEnabled,
                onShowMemoMenu = showMenu,
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
    uiState: MainViewModel.MainScreenState,
    hasItems: Boolean,
    uiMemos: List<MemoUiModel>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isRefreshing: Boolean,
    onVisibleMemoIdsChanged: (Set<String>) -> Unit,
    onTodoClick: (com.lomo.domain.model.Memo, Int, Boolean) -> Unit,
    dateFormat: String,
    timeFormat: String,
    onMemoDoubleClick: (com.lomo.domain.model.Memo) -> Unit,
    doubleTapEditEnabled: Boolean,
    onShowMemoMenu: (com.lomo.ui.component.menu.MemoMenuState) -> Unit,
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
            modifier = Modifier.fillMaxWidth(),
        )
    }

    val screenContent: @Composable () -> Unit = {
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
                    onClearFilter = actions.onClearFilter,
                    isFilterActive = selectedTag != null,
                    showNavigationIcon = !isExpanded,
                )
            },
            floatingActionButton = {
                MainFab(
                    isVisible = scrollBehavior.state.collapsedFraction < 0.9f,
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
                            if (!hasItems) {
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
                                    onVisibleMemoIdsChanged = onVisibleMemoIdsChanged,
                                    onTodoClick = onTodoClick,
                                    dateFormat = dateFormat,
                                    timeFormat = timeFormat,
                                    onMemoDoubleClick = onMemoDoubleClick,
                                    doubleTapEditEnabled = doubleTapEditEnabled,
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
        MainVersionHistoryState.Hidden -> Unit
    }
}
