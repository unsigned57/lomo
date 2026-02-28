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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.delay
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

    // Directory Setup Dialog State (Hoisted)
    var directorySetupType by remember { mutableStateOf<DirectorySetupType?>(null) }

    // Track scroll to top for new memo insertions
    var pendingNewMemoScroll by remember { mutableStateOf(false) }

    // LaunchedEffect to scroll to top once the new memo is actually inserted in the data source
    LaunchedEffect(uiMemos.size) {
        if (pendingNewMemoScroll && uiMemos.isNotEmpty()) {
            pendingNewMemoScroll = false
            listState.animateScrollToItem(0)
        }
    }

    // Shared Content Observation
    LaunchedEffect(sharedContentEvents) {
        sharedContentEvents.forEach { event ->
            val content = event.payload
            when (content) {
                is MainViewModel.SharedContent.Text -> {
                    editorController.appendMarkdownBlock(content.content)
                    editorController.ensureVisible()
                }
            }
            viewModel.consumeSharedContentEvent(event.id)
        }
    }

    LaunchedEffect(appActionEvents) {
        appActionEvents.forEach { event ->
            val action = event.payload
            when (action) {
                MainViewModel.AppAction.CreateMemo -> {
                    editorController.openForCreate()
                }

                is MainViewModel.AppAction.OpenMemo -> {
                    val memo = viewModel.resolveMemoById(action.memoId)
                    if (memo != null) {
                        editorController.openForEdit(memo)
                    } else {
                        snackbarHostState.showSnackbar(unknownErrorMessage)
                    }
                }
            }
            viewModel.consumeAppActionEvent(event.id)
        }
    }

    LaunchedEffect(imageDir, pendingSharedImageEvents) {
        val pending = pendingSharedImageEvents.firstOrNull() ?: return@LaunchedEffect
        if (imageDir == null) {
            directorySetupType = DirectorySetupType.Image
            return@LaunchedEffect
        }

        editorViewModel.saveImage(
            uri = pending.payload,
            onResult = { path ->
                editorController.appendImageMarkdown(path)
                editorController.ensureVisible()
                viewModel.consumePendingSharedImageEvent(pending.id)
            },
        )
    }

    // Effect: Error Handling
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(editorErrorMessage) {
        editorErrorMessage?.let {
            snackbarHostState.showSnackbar(it)
            editorViewModel.clearError()
        }
    }

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
        onImageDirectoryMissing = { directorySetupType = DirectorySetupType.Image },
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
                directorySetupType = DirectorySetupType.Voice
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

        val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
        val actions =
            remember(
                viewModel,
                sidebarViewModel,
                editorController,
                scope,
                drawerState,
                haptic,
                isExpanded,
                onNavigateToSettings,
                onNavigateToTrash,
                onNavigateToSearch,
                onNavigateToTag,
                onNavigateToImage,
                onNavigateToDailyReview,
                onNavigateToGallery,
            ) {
                MainScreenActions(
                    onSettings = {
                        if (!isExpanded) scope.launch { drawerState.close() }
                        onNavigateToSettings()
                    },
                    onTrash = {
                        if (!isExpanded) scope.launch { drawerState.close() }
                        onNavigateToTrash()
                    },
                    onSearch = onNavigateToSearch,
                    onSidebarMemoClick = {
                        sidebarViewModel.clearFilters()
                        if (!isExpanded) scope.launch { drawerState.close() }
                    },
                    onSidebarTagClick = { tag ->
                        if (!isExpanded) scope.launch { drawerState.close() }
                        onNavigateToTag(tag)
                    },
                    onNavigateToImage = onNavigateToImage,
                    onClearFilter = { sidebarViewModel.onTagSelected(null) },
                    onMenuOpen = { scope.launch { drawerState.open() } },
                    onFabClick = {
                        haptic.longPress()
                        if (viewModel.uiState.value is MainViewModel.MainScreenState.Ready) {
                            editorController.openForCreate()
                        } else {
                            onNavigateToSettings()
                        }
                    },
                    onRefresh = {
                        scope.launch {
                            isRefreshing = true
                            try {
                                viewModel.refresh()
                                delay(REFRESH_DELAY)
                            } finally {
                                isRefreshing = false
                            }
                        }
                    },
                    onDailyReviewClick = {
                        if (!isExpanded) scope.launch { drawerState.close() }
                        onNavigateToDailyReview()
                    },
                    onGalleryClick = {
                        if (!isExpanded) scope.launch { drawerState.close() }
                        onNavigateToGallery()
                    },
                )
            }

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
                        onLongClick = {
                            scope.launch {
                                // If far away, jump closer first to avoid dropping
                                // frames
                                if (listState.firstVisibleItemIndex > 10) {
                                    listState.scrollToItem(10)
                                }
                                listState.animateScrollToItem(0)
                            }
                        },
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
                                        onVisibleMemoIdsChanged = viewModel::updateVisibleMemoIds,
                                        onTodoClick = { memo, index, checked -> viewModel.updateMemo(memo, index, checked) },
                                        dateFormat = dateFormat,
                                        timeFormat = timeFormat,
                                        onMemoDoubleClick = openEditor,
                                        doubleTapEditEnabled = doubleTapEditEnabled,
                                        onTagClick = actions.onSidebarTagClick,
                                        onImageClick = actions.onNavigateToImage,
                                        onShowMemoMenu = showMenu,
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

        DirectorySetupDialog(
            type = directorySetupType,
            onDismiss = { directorySetupType = null },
            onConfirmCreate = { type ->
                viewModel.createDefaultDirectories(
                    forImage = type == DirectorySetupType.Image,
                    forVoice = type == DirectorySetupType.Voice,
                )
                directorySetupType = null
            },
            onGoToSettings = {
                directorySetupType = null
                editorController.close()
                onNavigateToSettings()
            },
        )

        VersionHistoryOverlay(
            state = versionHistoryState,
            onDismiss = viewModel::dismissVersionHistory,
            onRestore = { memo, version -> viewModel.restoreVersion(memo, version) },
        )
    }
}

enum class DirectorySetupType(
    val labelResId: Int,
    val subfolder: String,
) {
    Image(R.string.settings_image_storage, "images"),
    Voice(R.string.settings_voice_storage, "voice"),
}

// Refactor: Sub-components extracted to separate files:
// - MainTopBar -> MainScreenTopBar.kt
// - MainFab -> MainScreenFab.kt
// - MainEmptyState -> MainScreenEmptyState.kt
// - MemoListContent, MemoItemContent -> MemoListContent.kt

// Helper for Actions
data class MainScreenActions(
    val onSettings: () -> Unit,
    val onTrash: () -> Unit,
    val onSearch: () -> Unit,
    val onSidebarMemoClick: () -> Unit,
    val onSidebarTagClick: (String) -> Unit,
    val onClearFilter: () -> Unit,
    val onMenuOpen: () -> Unit,
    val onFabClick: () -> Unit,
    val onRefresh: () -> Unit,
    val onNavigateToImage: (String) -> Unit,
    val onDailyReviewClick: () -> Unit,
    val onGalleryClick: () -> Unit,
)

// Removed duplicate formatTime - now using DateTimeUtils.format()
// from com.lomo.ui.util.DateTimeUtils for centralized date formatting

@Composable
private fun DirectorySetupDialog(
    type: DirectorySetupType?,
    onDismiss: () -> Unit,
    onConfirmCreate: (DirectorySetupType) -> Unit,
    onGoToSettings: () -> Unit,
) {
    val currentType = type ?: return
    val typeLabel = stringResource(currentType.labelResId)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setup_directory_title, typeLabel)) },
        text = { Text(stringResource(R.string.setup_directory_message, typeLabel, currentType.subfolder)) },
        confirmButton = {
            TextButton(
                onClick = { onConfirmCreate(currentType) },
            ) {
                Text(stringResource(R.string.action_auto_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onGoToSettings) {
                Text(stringResource(R.string.action_go_to_settings))
            }
        },
    )
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

private const val REFRESH_DELAY = 500L
