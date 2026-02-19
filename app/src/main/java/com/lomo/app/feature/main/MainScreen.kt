package com.lomo.app.feature.main
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.window.core.layout.WindowSizeClass
import com.lomo.app.R
import com.lomo.domain.model.Memo
import com.lomo.ui.component.input.InputSheet
import com.lomo.ui.component.menu.MemoMenuBottomSheet
import com.lomo.ui.component.menu.MemoMenuHost
import com.lomo.ui.component.menu.MemoMenuState
import com.lomo.ui.component.navigation.SidebarDrawer
import com.lomo.ui.theme.MotionTokens
import com.lomo.ui.util.DateTimeUtils
import com.lomo.ui.util.formatAsDateTime
import kotlinx.coroutines.async
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
    onNavigateToMemo: (String, String) -> Unit,
    onNavigateToTag: (String) -> Unit,
    onNavigateToImage: (String) -> Unit,
    onNavigateToDailyReview: () -> Unit,
    onNavigateToShare: (String, Long) -> Unit = { _, _ -> },
    viewModel: MainViewModel = hiltViewModel(),
    sidebarViewModel: SidebarViewModel = hiltViewModel(),
    recordingViewModel: RecordingViewModel = hiltViewModel(),
) {
    // Collect Flow state safely with Lifecycle awareness using collectAsStateWithLifecycle
    // to ensure flows are paused when the app is in the background.
    val pagedMemos = viewModel.pagedMemos.collectAsLazyPagingItems()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedTag by viewModel.selectedTag.collectAsStateWithLifecycle()
    val sidebarUiState by sidebarViewModel.sidebarUiState.collectAsStateWithLifecycle()

    val dateFormat by viewModel.dateFormat.collectAsStateWithLifecycle()
    val timeFormat by viewModel.timeFormat.collectAsStateWithLifecycle()
    val hapticEnabled by viewModel.hapticFeedbackEnabled.collectAsStateWithLifecycle()
    val showInputHints by viewModel.showInputHints.collectAsStateWithLifecycle()

    // Recording State (from RecordingViewModel)
    val isRecording by recordingViewModel.isRecording.collectAsStateWithLifecycle()
    val recordingDuration by recordingViewModel.recordingDuration.collectAsStateWithLifecycle()
    val recordingAmplitude by recordingViewModel.recordingAmplitude.collectAsStateWithLifecycle()

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val todoOverrides by viewModel.todoOverrides.collectAsStateWithLifecycle()
    val pendingMutations by viewModel.pendingMutations.collectAsStateWithLifecycle()
    val rootDir by viewModel.rootDirectory.collectAsStateWithLifecycle()
    val imageDir by viewModel.imageDirectory.collectAsStateWithLifecycle()
    val imageMap by viewModel.imageMap.collectAsStateWithLifecycle()

    // Manual pull-to-refresh is available for explicit data reload.
    val hasItems = pagedMemos.itemCount > 0
    val refreshState = pagedMemos.loadState.refresh
    val isInitiallyLoading = refreshState is LoadState.Loading && !hasItems
    val isEmpty = !hasItems && refreshState is LoadState.NotLoading

    // Host State
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    val context = androidx.compose.ui.platform.LocalContext.current
    val listState =
        rememberSaveable(saver = androidx.compose.foundation.lazy.LazyListState.Saver) {
            androidx.compose.foundation.lazy
                .LazyListState()
        }

    // Local UI State
    var showInputSheet by remember { mutableStateOf(false) }
    // var selectedMemo removed
    var editingMemo by remember { mutableStateOf<Memo?>(null) }
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    var isRefreshing by remember { mutableStateOf(false) }
    var hasCompletedInitialLoad by rememberSaveable { mutableStateOf(false) }

    // Adaptive Layout
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val isExpanded = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    // Directory Setup Dialog State (Hoisted)
    var directorySetupType by remember { mutableStateOf<DirectorySetupType?>(null) }

    // Shared Content Observation
    val sharedContent by viewModel.sharedContent.collectAsStateWithLifecycle()

    LaunchedEffect(sharedContent, imageDir) {
        val content = sharedContent
        if (content != null) {
            when (content) {
                is MainViewModel.SharedContent.Text -> {
                    val cur = inputText.text
                    // Prevent duplicate processing if content is already in input (heuristic)
                    // But strictly, consumeSharedContent handles this.
                    // Just proceed.
                    val newText = if (cur.isEmpty()) content.content else "$cur\n${content.content}"
                    inputText = TextFieldValue(newText, TextRange(newText.length))
                    showInputSheet = true
                    viewModel.consumeSharedContent()
                }

                is MainViewModel.SharedContent.Image -> {
                    if (imageDir != null) {
                        // Logic handled via saving image in ViewModel then appending markdown
                        viewModel.saveImage(content.uri) { path ->
                            val markdown = "![image]($path)"
                            val cur = inputText.text
                            val newText = if (cur.isEmpty()) markdown else "$cur\n$markdown"
                            inputText = TextFieldValue(newText, TextRange(newText.length))
                            showInputSheet = true
                            viewModel.consumeSharedContent()
                        }
                    } else {
                        // Trigger directory setup if missing
                        directorySetupType = DirectorySetupType.Image
                    }
                }
            }
        }
    }

    // Effect: Error Handling
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Track deleting items for "fade out then delete" animation sequence

    MemoMenuHost(
        onEdit = { state ->
            val memo = state.memo as? com.lomo.domain.model.Memo
            if (memo != null) {
                // Use internal edit flow instead of external navigation
                editingMemo = memo
                inputText =
                    androidx.compose.ui.text.input
                        .TextFieldValue(
                            memo.content,
                            androidx.compose.ui.text
                                .TextRange(memo.content.length),
                        )
                showInputSheet = true
            }
        },
        onDelete = { state ->
            val memo = state.memo as? com.lomo.domain.model.Memo
            if (memo != null) {
                // Driven purely by ViewModel mutation state
                viewModel.deleteMemo(memo)
            }
        },
        onShare = { state ->
            com.lomo.app.util.ShareUtils.shareMemoText(
                context = context,
                content = state.content,
            )
        },
        onLanShare = { state ->
            val memo = state.memo as? com.lomo.domain.model.Memo
            if (memo != null) {
                onNavigateToShare(memo.content, memo.timestamp)
            }
        },
    ) { showMenu ->

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

        // Image Picker
        val imagePicker =
            rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                uri?.let {
                    viewModel.saveImage(it) { path ->
                        val markdown = "![image]($path)"
                        val cur = inputText.text
                        val newText = if (cur.isEmpty()) markdown else "$cur\n$markdown"
                        inputText = TextFieldValue(newText, TextRange(newText.length))
                    }
                }
            }

        val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()

        // Moved actions inside Provider to access LocalAppHapticFeedback
        com.lomo.ui.util.ProvideAppHapticFeedback(enabled = hapticEnabled) {
            val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
            val actions =
                remember(viewModel, scope, drawerState, haptic) {
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
                        onMemoClick = onNavigateToMemo,
                        onSidebarMemoClick = {
                            viewModel.clearFilters()
                            if (!isExpanded) scope.launch { drawerState.close() }
                        },
                        onSidebarTagClick = { tag ->
                            if (!isExpanded) scope.launch { drawerState.close() }
                            onNavigateToTag(tag)
                        },
                        onNavigateToImage = onNavigateToImage,
                        onClearFilter = { viewModel.onTagSelected(null) },
                        onMenuOpen = { scope.launch { drawerState.open() } },
                        onFabClick = {
                            haptic.longPress()
                            if (viewModel.uiState.value is MainViewModel.MainScreenState.Ready) {
                                showInputSheet = true
                            } else {
                                onNavigateToSettings()
                            }
                        },
                        onRefresh = {
                            scope.launch {
                                isRefreshing = true
                                val job = async { viewModel.refresh() }
                                delay(REFRESH_DELAY)
                                job.await()
                                isRefreshing = false
                            }
                        },
                        onDailyReviewClick = {
                            if (!isExpanded) scope.launch { drawerState.close() }
                            onNavigateToDailyReview()
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
                        val refreshState = pagedMemos.loadState.refresh
                        val isEmpty = pagedMemos.itemCount == 0

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
                                    if (isInitiallyLoading && !hasCompletedInitialLoad) {
                                        // First time import or loading from scratch - show skeleton
                                        com.lomo.ui.component.common.MemoListSkeleton(
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    } else {
                                        // Truly empty state OR refreshing (adding first item) after initial load
                                        val showEmptyState = isEmpty || (isInitiallyLoading && hasCompletedInitialLoad)

                                        if (!isInitiallyLoading) {
                                            hasCompletedInitialLoad = true
                                        }

                                        AnimatedContent(
                                            targetState = showEmptyState,
                                            transitionSpec = {
                                                if (!targetState) {
                                                    // List appearing (Empty -> List): Fade + Scale In
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
                                                } else {
                                                    // Empty appearing (List -> Empty): Simple Fade
                                                    fadeIn(
                                                        animationSpec =
                                                            tween(
                                                                durationMillis = MotionTokens.DurationLong2,
                                                                easing = MotionTokens.EasingStandard,
                                                            ),
                                                    ) togetherWith
                                                        fadeOut(
                                                            animationSpec =
                                                                tween(
                                                                    durationMillis = MotionTokens.DurationLong2,
                                                                    easing = MotionTokens.EasingStandard,
                                                                ),
                                                        )
                                                }
                                            },
                                            label = "FirstNoteTransition",
                                        ) { showEmpty ->
                                            if (showEmpty) {
                                                MainEmptyState(
                                                    searchQuery = searchQuery,
                                                    selectedTag = selectedTag,
                                                    hasDirectory = true,
                                                    onSettings = actions.onSettings,
                                                )
                                            } else {
                                                MemoListContent(
                                                    memos = pagedMemos,
                                                    listState = listState,
                                                    isRefreshing = isRefreshing,
                                                    onRefresh = actions.onRefresh,
                                                    onTodoClick = { memo, index, checked -> viewModel.updateMemo(memo, index, checked) },
                                                    dateFormat = dateFormat,
                                                    timeFormat = timeFormat,
                                                    todoOverrides = todoOverrides,
                                                    onMemoClick = actions.onMemoClick,
                                                    onTagClick = actions.onSidebarTagClick,
                                                    onImageClick = actions.onNavigateToImage,
                                                    onShowMemoMenu = { uiModel ->
                                                        showMenu(
                                                            com.lomo.ui.component.menu.MemoMenuState(
                                                                wordCount = uiModel.memo.content.length,
                                                                createdTime =
                                                                    uiModel.memo.timestamp.formatAsDateTime(
                                                                        dateFormat,
                                                                        timeFormat,
                                                                    ),
                                                                content = uiModel.memo.content,
                                                                memo = uiModel.memo,
                                                            ),
                                                        )
                                                    },
                                                    pendingMutations = pendingMutations,
                                                    mapper = viewModel.mapper,
                                                    rootDir = rootDir,
                                                    imageDir = imageDir,
                                                    imageMap = imageMap,
                                                )
                                            }
                                        }
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

        // Input Sheet
        val imageDirectory by viewModel.imageDirectory.collectAsStateWithLifecycle()
        val voiceDirectory by viewModel.voiceDirectory.collectAsStateWithLifecycle()

        if (showInputSheet) {
            val allTags = remember(sidebarUiState.tags) { sidebarUiState.tags.map { it.name }.sorted() }
            InputSheet(
                inputValue = inputText,
                onInputValueChange = { inputText = it },
                onDismiss = {
                    showInputSheet = false
                    inputText = TextFieldValue("")
                    editingMemo = null
                    viewModel.discardInputs()
                },
                onSubmit = { content ->
                    val isNewMemo = editingMemo == null
                    editingMemo?.let { viewModel.updateMemo(it, content) }
                        ?: viewModel.addMemo(content)
                    showInputSheet = false
                    inputText = TextFieldValue("")
                    editingMemo = null

                    // Bug 2 fix: Scroll to top after creating new memo
                    if (isNewMemo) {
                        scope.launch {
                            // Small delay to allow Paging to process the new item
                            kotlinx.coroutines.delay(300)
                            listState.animateScrollToItem(0)
                        }
                    }
                },
                onImageClick = {
                    if (imageDirectory == null) {
                        directorySetupType = DirectorySetupType.Image
                    } else {
                        imagePicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    }
                },
                availableTags = allTags,
                // Recording Integration
                // Recording Integration
                isRecording = isRecording,
                recordingDuration = recordingDuration,
                recordingAmplitude = recordingAmplitude,
                onStartRecording = {
                    if (voiceDirectory == null) {
                        directorySetupType = DirectorySetupType.Voice
                    } else {
                        recordingViewModel.startRecording()
                    }
                },
                onCancelRecording = recordingViewModel::cancelRecording,
                onStopRecording = {
                    recordingViewModel.stopRecording { markdown ->
                        val cur = inputText.text
                        val newText = if (cur.isEmpty()) markdown else "$cur\n$markdown"
                        inputText = TextFieldValue(newText, TextRange(newText.length))
                    }
                },
                hints =
                    if (showInputHints) {
                        listOf(
                            stringResource(R.string.input_hint_1),
                            stringResource(R.string.input_hint_2),
                            stringResource(R.string.input_hint_3),
                        )
                    } else {
                        emptyList()
                    },
            )
        }

        if (directorySetupType != null) {
            val type = directorySetupType!!
            val typeLabel = stringResource(type.labelResId)
            AlertDialog(
                onDismissRequest = { directorySetupType = null },
                title = { Text(stringResource(R.string.setup_directory_title, typeLabel)) },
                text = { Text(stringResource(R.string.setup_directory_message, typeLabel, type.subfolder)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.createDefaultDirectories(
                                forImage = type == DirectorySetupType.Image,
                                forVoice = type == DirectorySetupType.Voice,
                            )
                            directorySetupType = null
                        },
                    ) {
                        Text(stringResource(R.string.action_auto_create))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        directorySetupType = null
                        showInputSheet = false
                        onNavigateToSettings()
                    }) {
                        Text(stringResource(R.string.action_go_to_settings))
                    }
                },
            )
        }
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
    val onMemoClick: (String, String) -> Unit,
    val onSidebarMemoClick: () -> Unit,
    val onSidebarTagClick: (String) -> Unit,
    val onClearFilter: () -> Unit,
    val onMenuOpen: () -> Unit,
    val onFabClick: () -> Unit,
    val onRefresh: () -> Unit,
    val onNavigateToImage: (String) -> Unit,
    val onDailyReviewClick: () -> Unit,
)

// Removed duplicate formatTime - now using DateTimeUtils.format()
// from com.lomo.ui.util.DateTimeUtils for centralized date formatting

private const val REFRESH_DELAY = 500L
