package com.lomo.app.feature.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.memo.MemoEditorController
import com.lomo.app.feature.memo.MemoEditorViewModel
import com.lomo.app.feature.memo.MemoInteractionHost
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoVersion
import com.lomo.ui.component.menu.MemoMenuState
import com.lomo.ui.theme.MotionTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal const val DRAFT_AUTOSAVE_DEBOUNCE_MILLIS = 500L
internal const val MAIN_SCREEN_LIST_SCROLL_SETTLE_INDEX = 10
internal const val MAIN_SCREEN_FAB_VISIBILITY_THRESHOLD = 0.9f
internal const val MAIN_SCREEN_MODAL_DRAWER_WIDTH_FRACTION = 0.8f

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
    val screenState = collectMainScreenUiSnapshot(viewModel = viewModel, sidebarViewModel = sidebarViewModel)
    val hostState = rememberMainScreenHostState()
    val unknownErrorMessage = stringResource(R.string.error_unknown)
    var isRefreshing by remember { mutableStateOf(false) }
    var pendingNewMemoScroll by remember { mutableStateOf(false) }

    MainScreenDraftAutosaveEffect(
        editorController = hostState.editorController,
        editorViewModel = editorViewModel,
    )
    MainScreenPendingScrollEffect(
        uiMemos = screenState.uiMemos,
        listState = hostState.listState,
        pendingNewMemoScroll = pendingNewMemoScroll,
        onPendingScrollConsumed = { pendingNewMemoScroll = false },
    )

    MainScreenTransientEffects(
        viewModel = viewModel,
        editorViewModel = editorViewModel,
        uiMemos = screenState.uiMemos,
        listState = hostState.listState,
        editorController = hostState.editorController,
        directoryGuideController = hostState.directoryGuideController,
        snackbarHostState = hostState.snackbarHostState,
        unknownErrorMessage = unknownErrorMessage,
    )
    MainScreenConflictHost(viewModel = viewModel, conflictViewModel = conflictViewModel)
    MainScreenContentHost(
        screenState = screenState,
        hostState = hostState,
        viewModel = viewModel,
        sidebarViewModel = sidebarViewModel,
        editorViewModel = editorViewModel,
        recordingViewModel = recordingViewModel,
        unknownErrorMessage = unknownErrorMessage,
        isRefreshing = isRefreshing,
        onRefreshingChange = { isRefreshing = it },
        onPendingNewMemoScroll = { pendingNewMemoScroll = true },
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToTrash = onNavigateToTrash,
        onNavigateToSearch = onNavigateToSearch,
        onNavigateToTag = onNavigateToTag,
        onNavigateToImage = onNavigateToImage,
        onNavigateToDailyReview = onNavigateToDailyReview,
        onNavigateToGallery = onNavigateToGallery,
        onNavigateToShare = onNavigateToShare,
    )
}

internal data class DraftAutosaveState(
    val editingMemoId: String?,
    val text: String,
    val isVisible: Boolean,
)

internal data class MainScreenUiSnapshot(
    val uiMemos: List<MemoUiModel>,
    val searchQuery: String,
    val selectedTag: String?,
    val memoListFilter: MemoListFilter,
    val sidebarUiState: SidebarViewModel.SidebarUiState,
    val dateFormat: String,
    val timeFormat: String,
    val showInputHints: Boolean,
    val doubleTapEditEnabled: Boolean,
    val freeTextCopyEnabled: Boolean,
    val quickSaveOnBackEnabled: Boolean,
    val shareCardShowTime: Boolean,
    val uiState: MainViewModel.MainScreenState,
    val hasItems: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
internal data class MainScreenHostState(
    val drawerState: androidx.compose.material3.DrawerState,
    val scope: CoroutineScope,
    val snackbarHostState: SnackbarHostState,
    val scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    val listState: androidx.compose.foundation.lazy.LazyListState,
    val editorController: MemoEditorController,
    val isExpanded: Boolean,
    val directoryGuideController: MainDirectoryGuideController,
)

internal typealias MainScreenInteractionContent =
    @Composable ((MemoMenuState) -> Unit, (Memo) -> Unit) -> Unit

private data class MainScreenInteractionCallbacks(
    val onCreateMemo: (String) -> Unit,
    val onCameraCaptureError: (Throwable) -> Unit,
    val onStartRecording: () -> Unit,
    val onStopRecording: () -> Unit,
    val onVersionHistory: (MemoMenuState) -> Unit,
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
    editorController: MemoEditorController,
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
        onResolveMemoById = viewModel.resolveMemoById,
        onSaveImage = { uri, onResult -> editorViewModel.saveImage(uri = uri, onResult = onResult) },
        onRequireImageDirectory = directoryGuideController::requestImage,
        onConsumeSharedContentEvent = viewModel.consumeSharedContentEvent,
        onConsumeAppActionEvent = viewModel.consumeAppActionEvent,
        onConsumePendingSharedImageEvent = viewModel.consumePendingSharedImageEvent,
        onClearMainError = viewModel.clearError,
        onClearEditorError = editorViewModel::clearError,
    )
}

@Composable
internal fun MainScreenInteractionBindings(
    viewModel: MainViewModel,
    editorViewModel: MemoEditorViewModel,
    recordingViewModel: RecordingViewModel,
    editorController: MemoEditorController,
    directoryGuideController: MainDirectoryGuideController,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    unknownErrorMessage: String,
    shareCardShowTime: Boolean,
    quickSaveOnBackEnabled: Boolean,
    availableTags: List<String>,
    showInputHints: Boolean,
    onNavigateToShare: (String, Long) -> Unit,
    onPendingNewMemoScroll: () -> Unit,
    content: MainScreenInteractionContent,
) {
    val activeDayCount by viewModel.activeDayCount.collectAsStateWithLifecycle()
    val gitSyncEnabled by viewModel.gitSyncEnabled.collectAsStateWithLifecycle()
    val versionHistoryState by viewModel.versionHistoryState.collectAsStateWithLifecycle()
    val imageDirectory by viewModel.imageDirectory.collectAsStateWithLifecycle()
    val voiceDirectory by viewModel.voiceDirectory.collectAsStateWithLifecycle()
    val inputHints = rememberInputHints(showInputHints = showInputHints)
    val interactionCallbacks =
        rememberMainScreenInteractionCallbacks(
            viewModel = viewModel,
            editorViewModel = editorViewModel,
            recordingViewModel = recordingViewModel,
            editorController = editorController,
            directoryGuideController = directoryGuideController,
            voiceDirectory = voiceDirectory,
            scope = scope,
            snackbarHostState = snackbarHostState,
            unknownErrorMessage = unknownErrorMessage,
            onPendingNewMemoScroll = onPendingNewMemoScroll,
        )

    MemoInteractionHost(
        shareCardShowTime = shareCardShowTime,
        activeDayCount = activeDayCount,
        imageDirectory = imageDirectory,
        controller = editorController,
        quickSaveOnBackEnabled = quickSaveOnBackEnabled,
        onDeleteMemo = viewModel.deleteMemo,
        onUpdateMemo = editorViewModel::updateMemo,
        onCreateMemo = interactionCallbacks.onCreateMemo,
        onSaveImage = editorViewModel::saveImage,
        onLanShare = onNavigateToShare,
        onDismiss = editorViewModel::discardInputs,
        onImageDirectoryMissing = directoryGuideController::requestImage,
        onCameraCaptureError = interactionCallbacks.onCameraCaptureError,
        availableTags = availableTags,
        isRecordingFlow = recordingViewModel.isRecording,
        recordingDurationFlow = recordingViewModel.recordingDuration,
        recordingAmplitudeFlow = recordingViewModel.recordingAmplitude,
        onStartRecording = interactionCallbacks.onStartRecording,
        onCancelRecording = recordingViewModel::cancelRecording,
        onStopRecording = interactionCallbacks.onStopRecording,
        hints = inputHints,
        onVersionHistory = interactionCallbacks.onVersionHistory,
        onTogglePin = viewModel.setMemoPinned,
        showVersionHistory = gitSyncEnabled,
    ) { showMenu, openEditor ->
        content(showMenu, openEditor)
    }

    VersionHistoryOverlay(
        state = versionHistoryState,
        onDismiss = viewModel.dismissVersionHistory,
        onRestore = { memo, version -> viewModel.restoreVersion(memo, version) },
    )
}

@Composable
private fun rememberMainScreenInteractionCallbacks(
    viewModel: MainViewModel,
    editorViewModel: MemoEditorViewModel,
    recordingViewModel: RecordingViewModel,
    editorController: MemoEditorController,
    directoryGuideController: MainDirectoryGuideController,
    voiceDirectory: String?,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    unknownErrorMessage: String,
    onPendingNewMemoScroll: () -> Unit,
): MainScreenInteractionCallbacks =
    remember(
        viewModel,
        editorViewModel,
        recordingViewModel,
        editorController,
        directoryGuideController,
        voiceDirectory,
        scope,
        snackbarHostState,
        unknownErrorMessage,
        onPendingNewMemoScroll,
    ) {
        MainScreenInteractionCallbacks(
            onCreateMemo = { contentText ->
                editorViewModel.createMemo(contentText) {
                    onPendingNewMemoScroll()
                }
            },
            onCameraCaptureError = { error ->
                scope.launch {
                    snackbarHostState.showSnackbar(error.message ?: unknownErrorMessage)
                }
            },
            onStartRecording = {
                if (voiceDirectory == null) {
                    directoryGuideController.requestVoice()
                } else {
                    recordingViewModel.startRecording()
                }
            },
            onStopRecording = {
                recordingViewModel.stopRecording { markdown ->
                    editorController.appendMarkdownBlock(markdown)
                }
            },
            onVersionHistory = { state ->
                val memo = state.memo as? Memo
                if (memo != null) {
                    viewModel.loadVersionHistory(memo)
                }
            },
        )
    }

@Composable
internal fun MainReadyStateEnterContainer(content: @Composable () -> Unit) {
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
    onRestore: (Memo, MemoVersion) -> Unit,
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
