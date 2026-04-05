package com.lomo.app.feature.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.memo.MemoEditorController
import com.lomo.app.feature.memo.MemoEditorViewModel
import com.lomo.app.feature.memo.MemoInteractionHost
import com.lomo.app.feature.memo.MemoVersionHistoryUiMapper
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoRevision
import com.lomo.ui.component.menu.MemoMenuState
import com.lomo.ui.theme.MotionTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val currentListTopMemoId = screenState.visibleUiMemos.firstOrNull()?.memo?.id
    val unknownErrorMessage = stringResource(R.string.error_unknown)
    var isRefreshing by remember { mutableStateOf(false) }

    MainScreenDraftAutosaveEffect(
        editorController = hostState.editorController,
        editorViewModel = editorViewModel,
    )
    MainScreenAutomaticRefreshEffect(
        viewModel = viewModel,
        uiState = screenState.uiState,
    )
    MainScreenPendingNewMemoCreationEffect(
        pendingRequest = screenState.pendingNewMemoCreationRequest,
        listState = hostState.listState,
        currentListTopMemoId = currentListTopMemoId,
        newMemoInsertAnimationSession = hostState.newMemoInsertAnimationSession,
        viewModel = viewModel,
        editorViewModel = editorViewModel,
    )
    MainScreenNewMemoInsertAnimationEffect(
        listState = hostState.listState,
        currentListTopMemoId = currentListTopMemoId,
        currentTopViewportMemoId = null,
        newMemoInsertAnimationSession = hostState.newMemoInsertAnimationSession,
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
        canOpenCreateMemo =
            screenState.uiState is MainViewModel.MainScreenState.Ready &&
                screenState.pendingNewMemoCreationRequest == null,
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

@Composable
private fun MainScreenAutomaticRefreshEffect(
    viewModel: MainViewModel,
    uiState: MainViewModel.MainScreenState,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestViewModel = rememberUpdatedState(viewModel)

    LaunchedEffect(uiState) {
        if (uiState is MainViewModel.MainScreenState.Ready) {
            latestViewModel.value.requestAutomaticRefreshForVisibleScreen()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    latestViewModel.value.requestAutomaticRefreshForVisibleScreen()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

@Composable
private fun MainScreenPendingNewMemoCreationEffect(
    pendingRequest: PendingNewMemoCreationRequest?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    currentListTopMemoId: String?,
    newMemoInsertAnimationSession: NewMemoInsertAnimationSession,
    viewModel: MainViewModel,
    editorViewModel: MemoEditorViewModel,
) {
    val scope = rememberCoroutineScope()
    val latestViewModel = rememberUpdatedState(viewModel)
    val latestEditorViewModel = rememberUpdatedState(editorViewModel)
    val latestListTopMemoId = rememberUpdatedState(currentListTopMemoId)
    val latestNewMemoInsertAnimationSession = rememberUpdatedState(newMemoInsertAnimationSession)
    val creationCoordinator =
        remember(listState, scope) {
            NewMemoCreationCoordinator<PendingNewMemoCreationRequest>(
                scope = scope,
                isListAtAbsoluteTop = {
                    listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                },
                scrollListToAbsoluteTop = {
                    listState.scrollToItem(0)
                },
                createMemo = { request ->
                    latestNewMemoInsertAnimationSession.value.arm(
                        previousTopMemoId = latestListTopMemoId.value,
                    )
                    val consumedRequest =
                        latestViewModel.value.consumePendingNewMemoCreationRequest(request.requestId)
                    if (consumedRequest != null) {
                        latestEditorViewModel.value.createMemo(consumedRequest.content)
                    }
                },
            )
        }

    LaunchedEffect(pendingRequest?.requestId) {
        pendingRequest?.let(creationCoordinator::submit)
    }
}

@Composable
private fun MainScreenNewMemoInsertAnimationEffect(
    listState: androidx.compose.foundation.lazy.LazyListState,
    currentListTopMemoId: String?,
    currentTopViewportMemoId: String?,
    newMemoInsertAnimationSession: NewMemoInsertAnimationSession,
) {
    val currentState = newMemoInsertAnimationSession.state
    val isListPinnedAtTop by
        remember(listState) {
            androidx.compose.runtime.derivedStateOf {
                listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            }
        }

    LaunchedEffect(
        currentListTopMemoId,
        currentTopViewportMemoId,
        currentState.awaitingInsertedTopMemo,
        currentState.blankSpaceMemoId,
        currentState.previousTopMemoId,
        currentState.gapReadyMemoId,
        isListPinnedAtTop,
    ) {
        when {
            currentState.awaitingInsertedTopMemo -> {
                withFrameNanos { }
                if (
                    isInsertedTopMemoReadyForSpaceStage(
                        state = currentState,
                        currentListTopMemoId = currentListTopMemoId,
                        isListPinnedAtTop = listState.firstVisibleItemIndex == 0 &&
                            listState.firstVisibleItemScrollOffset == 0,
                    )
                ) {
                    newMemoInsertAnimationSession.markInsertedTopMemoReady(
                        insertedTopMemoId = currentListTopMemoId,
                    )
                }
            }

            currentState.gapReadyMemoId != null -> {
                withFrameNanos { }
                if (newMemoInsertAnimationSession.state.gapReadyMemoId == currentState.gapReadyMemoId) {
                    newMemoInsertAnimationSession.markRevealReady(currentState.gapReadyMemoId)
                }
            }
        }
    }
}

internal data class DraftAutosaveState(
    val editingMemoId: String?,
    val text: String,
    val isVisible: Boolean,
)

internal data class MainScreenUiSnapshot(
    val uiMemos: List<MemoUiModel>,
    val visibleUiMemos: List<MemoUiModel>,
    val hasRawItems: Boolean,
    val searchQuery: String,
    val memoListFilter: MemoListFilter,
    val sidebarUiState: SidebarViewModel.SidebarUiState,
    val dateFormat: String,
    val timeFormat: String,
    val showInputHints: Boolean,
    val doubleTapEditEnabled: Boolean,
    val freeTextCopyEnabled: Boolean,
    val memoActionAutoReorderEnabled: Boolean,
    val memoActionOrder: List<String>,
    val quickSaveOnBackEnabled: Boolean,
    val shareCardShowTime: Boolean,
    val uiState: MainViewModel.MainScreenState,
    val pendingNewMemoCreationRequest: PendingNewMemoCreationRequest?,
)

@OptIn(ExperimentalMaterial3Api::class)
internal data class MainScreenHostState(
    val drawerState: androidx.compose.material3.DrawerState,
    val scope: CoroutineScope,
    val snackbarHostState: SnackbarHostState,
    val scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    val listState: androidx.compose.foundation.lazy.LazyListState,
    val newMemoInsertAnimationSession: NewMemoInsertAnimationSession,
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
    val hint3 = stringResource(R.string.input_hint_3)
    val hint4 = stringResource(R.string.input_hint_4)
    val hint5 = stringResource(R.string.input_hint_5)
    val hint6 = stringResource(R.string.input_hint_6)
    val hint7 = stringResource(R.string.input_hint_7)

    return remember(showInputHints, hint1, hint3, hint4, hint5, hint6, hint7) {
        if (!showInputHints) {
            emptyList()
        } else {
            listOf(hint1, hint3, hint4, hint5, hint6, hint7)
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
    canOpenCreateMemo: Boolean,
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
        onOpenCreateMemo = {
            if (canOpenCreateMemo) {
                editorController.openForCreate(draftText)
            }
        },
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
    memoActionAutoReorderEnabled: Boolean,
    memoActionOrder: List<String>,
    availableTags: List<String>,
    showInputHints: Boolean,
    onNavigateToShare: (String, Long) -> Unit,
    content: MainScreenInteractionContent,
) {
    val activeDayCount by viewModel.activeDayCount.collectAsStateWithLifecycle()
    val gitSyncEnabled by viewModel.gitSyncEnabled.collectAsStateWithLifecycle()
    val versionHistoryState by viewModel.versionHistoryState.collectAsStateWithLifecycle()
    val rootDirectory by viewModel.rootDirectory.collectAsStateWithLifecycle()
    val imageDirectory by viewModel.imageDirectory.collectAsStateWithLifecycle()
    val imageMap by viewModel.imageMap.collectAsStateWithLifecycle()
    val voiceDirectory by viewModel.voiceDirectory.collectAsStateWithLifecycle()
    val inputHints = rememberInputHints(showInputHints = showInputHints)
    val interactionCallbacks =
        rememberMainScreenInteractionCallbacks(
            viewModel = viewModel,
            recordingViewModel = recordingViewModel,
            editorController = editorController,
            directoryGuideController = directoryGuideController,
            voiceDirectory = voiceDirectory,
            scope = scope,
            snackbarHostState = snackbarHostState,
            unknownErrorMessage = unknownErrorMessage,
        )

    MemoInteractionHost(
        shareCardShowTime = shareCardShowTime,
        activeDayCount = activeDayCount,
        imageDirectory = imageDirectory,
        controller = editorController,
        quickSaveOnBackEnabled = quickSaveOnBackEnabled,
        memoActionAutoReorderEnabled = memoActionAutoReorderEnabled,
        memoActionOrder = memoActionOrder,
        onMemoActionInvoked = viewModel::recordMemoActionUsage,
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
        showVersionHistory = true,
    ) { showMenu, openEditor ->
        content(showMenu, openEditor)
    }

    VersionHistoryOverlay(
        state = versionHistoryState,
        rootPath = rootDirectory,
        imagePath = imageDirectory,
        imageMap = imageMap,
        onDismiss = viewModel.dismissVersionHistory,
        onLoadMore = viewModel.loadMoreVersionHistory,
        onRestore = { memo, version -> viewModel.restoreVersion(memo, version) },
    )
}

@Composable
private fun rememberMainScreenInteractionCallbacks(
    viewModel: MainViewModel,
    recordingViewModel: RecordingViewModel,
    editorController: MemoEditorController,
    directoryGuideController: MainDirectoryGuideController,
    voiceDirectory: String?,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    unknownErrorMessage: String,
): MainScreenInteractionCallbacks =
    remember(
        viewModel,
        recordingViewModel,
        editorController,
        directoryGuideController,
        voiceDirectory,
        scope,
        snackbarHostState,
        unknownErrorMessage,
    ) {
        MainScreenInteractionCallbacks(
            onCreateMemo = { contentText ->
                viewModel.requestPendingNewMemoCreation(contentText)
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
    rootPath: String?,
    imagePath: String?,
    imageMap: Map<String, android.net.Uri>,
    onDismiss: () -> Unit,
    onLoadMore: () -> Unit,
    onRestore: (Memo, MemoRevision) -> Unit,
) {
    val mapper = remember { MemoVersionHistoryUiMapper() }
    when (state) {
        is MainVersionHistoryState.Loading -> {
            com.lomo.app.feature.memo.MemoVersionHistorySheet(
                versions = emptyList(),
                isLoading = true,
                canLoadMore = false,
                isLoadingMore = false,
                isRestoreInProgress = false,
                restoringRevisionId = null,
                onLoadMore = {},
                onRestore = {},
                onDismiss = onDismiss,
            )
        }

        is MainVersionHistoryState.Loaded -> {
            var pendingRestoreRevisionId by remember(state.memo.id) { mutableStateOf<String?>(null) }
            var versionUiModels by
                remember {
                    mutableStateOf<List<com.lomo.app.feature.memo.MemoVersionHistoryUiModel>>(
                        emptyList(),
                    )
                }
            LaunchedEffect(state.isRestoring, state.restoringRevisionId, state.memo.id) {
                if (!state.isRestoring && state.restoringRevisionId == null) {
                    pendingRestoreRevisionId = null
                }
            }
            LaunchedEffect(state.versions, rootPath, imagePath, imageMap) {
                val revisions = state.versions
                versionUiModels =
                    withContext(Dispatchers.Default) {
                        mapper.mapToUiModels(
                            revisions = revisions,
                            rootPath = rootPath,
                            imagePath = imagePath,
                            imageMap = imageMap,
                        )
                    }
            }
            val restoringRevisionId = pendingRestoreRevisionId ?: state.restoringRevisionId
            val isRestoreInProgress = state.isRestoring || restoringRevisionId != null
            com.lomo.app.feature.memo.MemoVersionHistorySheet(
                versions = versionUiModels,
                isLoading = versionUiModels.isEmpty() && state.versions.isNotEmpty(),
                canLoadMore = state.hasMore,
                isLoadingMore = state.isLoadingMore,
                isRestoreInProgress = isRestoreInProgress,
                restoringRevisionId = restoringRevisionId,
                onLoadMore = onLoadMore,
                onRestore = { version ->
                    if (restoringRevisionId == null && !state.isRestoring) {
                        pendingRestoreRevisionId = version.revisionId
                        onRestore(state.memo, version)
                    }
                },
                onDismiss = onDismiss,
            )
        }

        MainVersionHistoryState.Hidden -> {
            Unit
        }
    }
}
