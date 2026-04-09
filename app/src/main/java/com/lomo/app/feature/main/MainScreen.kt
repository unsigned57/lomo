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
import com.lomo.app.util.activityHiltViewModel
import com.lomo.app.util.injectedHiltViewModel
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoRevision
import com.lomo.ui.component.menu.MemoMenuState
import com.lomo.ui.theme.MotionTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val DRAFT_AUTOSAVE_DEBOUNCE_MILLIS = 500L
internal const val MAIN_SCREEN_LIST_SCROLL_SETTLE_INDEX = 10
internal const val MAIN_SCREEN_FAB_VISIBILITY_THRESHOLD = 0.9f
internal const val MAIN_SCREEN_MODAL_DRAWER_WIDTH_FRACTION = 0.8f

internal data class MainScreenDependencies(
    val mainViewModel: MainViewModel,
    val sidebarViewModel: SidebarViewModel,
    val editorViewModel: MemoEditorViewModel,
    val recordingViewModel: RecordingViewModel,
    val conflictViewModel: com.lomo.app.feature.conflict.SyncConflictViewModel,
)

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
    viewModel: MainViewModel = activityHiltViewModel(),
    sidebarViewModel: SidebarViewModel = injectedHiltViewModel(),
    editorViewModel: MemoEditorViewModel = injectedHiltViewModel(),
    recordingViewModel: RecordingViewModel = injectedHiltViewModel(),
    conflictViewModel: com.lomo.app.feature.conflict.SyncConflictViewModel = injectedHiltViewModel(),
) {
    val dependencies =
        remember(viewModel, sidebarViewModel, editorViewModel, recordingViewModel, conflictViewModel) {
            MainScreenDependencies(
                mainViewModel = viewModel,
                sidebarViewModel = sidebarViewModel,
                editorViewModel = editorViewModel,
                recordingViewModel = recordingViewModel,
                conflictViewModel = conflictViewModel,
            )
        }
    val screenState = collectMainScreenUiSnapshot(dependencies = dependencies)
    val hostState = rememberMainScreenHostState()
    val currentListTopMemoId = screenState.visibleUiMemos.firstOrNull()?.memo?.id
    val unknownErrorMessage = stringResource(R.string.error_unknown)
    var isRefreshing by remember { mutableStateOf(false) }

    MainScreenDraftAutosaveEffect(
        editorController = hostState.editorController,
        dependencies = dependencies,
    )
    MainScreenAutomaticRefreshEffect(
        onRequestAutomaticRefresh = dependencies.mainViewModel.requestAutomaticRefreshForVisibleScreen,
        uiState = screenState.uiState,
    )
    MainScreenPendingNewMemoCreationEffect(
        pendingRequest = screenState.pendingNewMemoCreationRequest,
        listState = hostState.listState,
        currentListTopMemoId = currentListTopMemoId,
        newMemoInsertAnimationSession = hostState.newMemoInsertAnimationSession,
        dependencies = dependencies,
    )
    MainScreenNewMemoInsertAnimationEffect(
        listState = hostState.listState,
        currentListTopMemoId = currentListTopMemoId,
        currentTopViewportMemoId = null,
        newMemoInsertAnimationSession = hostState.newMemoInsertAnimationSession,
    )

    MainScreenTransientEffects(
        dependencies = dependencies,
        visibleUiMemos = screenState.visibleUiMemos,
        listState = hostState.listState,
        editorController = hostState.editorController,
        directoryGuideController = hostState.directoryGuideController,
        snackbarHostState = hostState.snackbarHostState,
        unknownErrorMessage = unknownErrorMessage,
        canOpenCreateMemo =
            screenState.uiState is MainViewModel.MainScreenState.Ready &&
                screenState.pendingNewMemoCreationRequest == null,
    )
    MainScreenConflictHost(dependencies = dependencies)
    MainScreenContentHost(
        screenState = screenState,
        hostState = hostState,
        dependencies = dependencies,
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
    onRequestAutomaticRefresh: () -> Unit,
    uiState: MainViewModel.MainScreenState,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestAutomaticRefresh = rememberUpdatedState(onRequestAutomaticRefresh)

    LaunchedEffect(uiState) {
        if (uiState is MainViewModel.MainScreenState.Ready) {
            latestAutomaticRefresh.value()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    latestAutomaticRefresh.value()
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
    dependencies: MainScreenDependencies,
) {
    val scope = rememberCoroutineScope()
    val latestDependencies = rememberUpdatedState(dependencies)
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
                        latestDependencies.value.mainViewModel.consumePendingNewMemoCreationRequest(request.requestId)
                    if (consumedRequest != null) {
                        latestDependencies.value.editorViewModel.createMemo(consumedRequest.content)
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
    val uiMemos: ImmutableList<MemoUiModel>,
    val visibleUiMemos: ImmutableList<MemoUiModel>,
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
    val memoActionOrder: ImmutableList<String>,
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
private fun rememberInputHints(showInputHints: Boolean): ImmutableList<String> {
    val hint1 = stringResource(R.string.input_hint_1)
    val hint2 = stringResource(R.string.input_hint_2)
    val hint3 = stringResource(R.string.input_hint_3)
    val hint4 = stringResource(R.string.input_hint_4)
    val hint5 = stringResource(R.string.input_hint_5)
    val hint6 = stringResource(R.string.input_hint_6)
    val hint7 = stringResource(R.string.input_hint_7)

    return remember(showInputHints, hint1, hint2, hint3, hint4, hint5, hint6, hint7) {
        if (!showInputHints) {
            persistentListOf()
        } else {
            persistentListOf(hint1, hint2, hint3, hint4, hint5, hint6, hint7)
        }
    }
}

@Composable
private fun MainScreenTransientEffects(
    dependencies: MainScreenDependencies,
    visibleUiMemos: ImmutableList<MemoUiModel>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    editorController: MemoEditorController,
    directoryGuideController: MainDirectoryGuideController,
    snackbarHostState: SnackbarHostState,
    unknownErrorMessage: String,
    canOpenCreateMemo: Boolean,
) {
    val errorMessage by dependencies.mainViewModel.errorMessage.collectAsStateWithLifecycle()
    val editorErrorMessage by dependencies.editorViewModel.errorMessage.collectAsStateWithLifecycle()
    val sharedContentEvents by dependencies.mainViewModel.sharedContentEvents.collectAsStateWithLifecycle()
    val pendingSharedImageEvents by dependencies.mainViewModel.pendingSharedImageEvents.collectAsStateWithLifecycle()
    val appActionEvents by dependencies.mainViewModel.appActionEvents.collectAsStateWithLifecycle()
    val imageDirectory by dependencies.mainViewModel.imageDirectory.collectAsStateWithLifecycle()
    val draftText by dependencies.editorViewModel.draftText.collectAsStateWithLifecycle()

    MainScreenEventEffectsHost(
        sharedContentEvents = remember(sharedContentEvents) { sharedContentEvents.toImmutableList() },
        appActionEvents = remember(appActionEvents) { appActionEvents.toImmutableList() },
        pendingSharedImageEvents = remember(pendingSharedImageEvents) { pendingSharedImageEvents.toImmutableList() },
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
            val index = visibleUiMemos.indexOfFirst { it.memo.id == memoId }
            if (index >= 0) {
                listState.animateScrollToItem(index)
                true
            } else {
                false
            }
        },
        onResolveMemoById = dependencies.mainViewModel.resolveMemoById,
        onSaveImage = { uri, onResult -> dependencies.editorViewModel.saveImage(uri = uri, onResult = onResult) },
        onRequireImageDirectory = directoryGuideController::requestImage,
        onConsumeSharedContentEvent = dependencies.mainViewModel.consumeSharedContentEvent,
        onConsumeAppActionEvent = dependencies.mainViewModel.consumeAppActionEvent,
        onConsumePendingSharedImageEvent = dependencies.mainViewModel.consumePendingSharedImageEvent,
        onClearMainError = dependencies.mainViewModel.clearError,
        onClearEditorError = dependencies.editorViewModel::clearError,
    )
}

@Composable
internal fun MainScreenInteractionBindings(
    dependencies: MainScreenDependencies,
    editorController: MemoEditorController,
    directoryGuideController: MainDirectoryGuideController,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    unknownErrorMessage: String,
    shareCardShowTime: Boolean,
    quickSaveOnBackEnabled: Boolean,
    memoActionAutoReorderEnabled: Boolean,
    memoActionOrder: ImmutableList<String>,
    availableTags: ImmutableList<String>,
    showInputHints: Boolean,
    onNavigateToShare: (String, Long) -> Unit,
    content: MainScreenInteractionContent,
) {
    val activeDayCount by dependencies.mainViewModel.activeDayCount.collectAsStateWithLifecycle()
    val gitSyncEnabled by dependencies.mainViewModel.gitSyncEnabled.collectAsStateWithLifecycle()
    val versionHistoryState by dependencies.mainViewModel.versionHistoryState.collectAsStateWithLifecycle()
    val rootDirectory by dependencies.mainViewModel.rootDirectory.collectAsStateWithLifecycle()
    val imageDirectory by dependencies.mainViewModel.imageDirectory.collectAsStateWithLifecycle()
    val imageMap by dependencies.mainViewModel.imageMap.collectAsStateWithLifecycle()
    val voiceDirectory by dependencies.mainViewModel.voiceDirectory.collectAsStateWithLifecycle()
    val inputHints = rememberInputHints(showInputHints = showInputHints)
    val interactionCallbacks =
        rememberMainScreenInteractionCallbacks(
            dependencies = dependencies,
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
        onMemoActionInvoked = dependencies.mainViewModel::recordMemoActionUsage,
        onDeleteMemo = dependencies.mainViewModel.deleteMemo,
        onUpdateMemo = dependencies.editorViewModel::updateMemo,
        onCreateMemo = interactionCallbacks.onCreateMemo,
        onSaveImage = dependencies.editorViewModel::saveImage,
        onLanShare = onNavigateToShare,
        onDismiss = dependencies.editorViewModel::discardInputs,
        onImageDirectoryMissing = directoryGuideController::requestImage,
        onCameraCaptureError = interactionCallbacks.onCameraCaptureError,
        availableTags = availableTags,
        isRecordingFlow = dependencies.recordingViewModel.isRecording,
        recordingDurationFlow = dependencies.recordingViewModel.recordingDuration,
        recordingAmplitudeFlow = dependencies.recordingViewModel.recordingAmplitude,
        onStartRecording = interactionCallbacks.onStartRecording,
        onCancelRecording = dependencies.recordingViewModel::cancelRecording,
        onStopRecording = interactionCallbacks.onStopRecording,
        hints = inputHints,
        onVersionHistory = interactionCallbacks.onVersionHistory,
        onTogglePin = dependencies.mainViewModel.setMemoPinned,
        showVersionHistory = true,
    ) { showMenu, openEditor ->
        content(showMenu, openEditor)
    }

    VersionHistoryOverlay(
        state = versionHistoryState,
        rootPath = rootDirectory,
        imagePath = imageDirectory,
        imageMap = remember(imageMap) { imageMap.toImmutableMap() },
        onDismiss = dependencies.mainViewModel.dismissVersionHistory,
        onLoadMore = dependencies.mainViewModel.loadMoreVersionHistory,
        onRestore = { memo, version -> dependencies.mainViewModel.restoreVersion(memo, version) },
    )
}

@Composable
private fun rememberMainScreenInteractionCallbacks(
    dependencies: MainScreenDependencies,
    editorController: MemoEditorController,
    directoryGuideController: MainDirectoryGuideController,
    voiceDirectory: String?,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    unknownErrorMessage: String,
): MainScreenInteractionCallbacks =
    remember(
        dependencies,
        editorController,
        directoryGuideController,
        voiceDirectory,
        scope,
        snackbarHostState,
        unknownErrorMessage,
    ) {
        MainScreenInteractionCallbacks(
            onCreateMemo = { contentText ->
                dependencies.mainViewModel.requestPendingNewMemoCreation(contentText)
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
                    dependencies.recordingViewModel.startRecording()
                }
            },
            onStopRecording = {
                dependencies.recordingViewModel.stopRecording { markdown ->
                    editorController.appendMarkdownBlock(markdown)
                }
            },
            onVersionHistory = { state ->
                val memo = state.memo as? Memo
                if (memo != null) {
                    dependencies.mainViewModel.loadVersionHistory(memo)
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
    imageMap: ImmutableMap<String, android.net.Uri>,
    onDismiss: () -> Unit,
    onLoadMore: () -> Unit,
    onRestore: (Memo, MemoRevision) -> Unit,
) {
    val mapper = remember { MemoVersionHistoryUiMapper() }
    when (state) {
        is MainVersionHistoryState.Loading -> {
            com.lomo.app.feature.memo.MemoVersionHistorySheet(
                versions = persistentListOf(),
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
                    mutableStateOf<ImmutableList<com.lomo.app.feature.memo.MemoVersionHistoryUiModel>>(
                        persistentListOf(),
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
                        ).toImmutableList()
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
