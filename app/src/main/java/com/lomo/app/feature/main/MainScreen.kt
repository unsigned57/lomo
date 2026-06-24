package com.lomo.app.feature.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.lomo.app.R
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.memo.MemoEditorController
import com.lomo.app.feature.memo.MemoEditorViewModel
import com.lomo.app.feature.memo.MemoInteractionHost
import com.lomo.app.feature.memo.MemoMenuPresentationState
import com.lomo.app.feature.memo.MemoVersionHistoryUiMapper
import com.lomo.app.feature.memo.appendImageMarkdown
import com.lomo.app.feature.memo.appendMarkdownBlock
import com.lomo.app.feature.memo.rememberMemoMenuCommandHandler
import com.lomo.app.feature.conflict.SyncConflictStateViewModel
import com.lomo.app.util.activityHiltViewModel
import com.lomo.app.util.injectedHiltViewModel
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoRevision
import com.lomo.app.feature.memo.MemoMenuSelection
import com.lomo.ui.theme.MotionTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal const val DRAFT_AUTOSAVE_DEBOUNCE_MILLIS = 500L
internal const val MAIN_SCREEN_LIST_SCROLL_SETTLE_INDEX = 10
internal const val MAIN_SCREEN_FAB_VISIBILITY_THRESHOLD = 0.9f
internal const val MAIN_SCREEN_MODAL_DRAWER_WIDTH_FRACTION = 0.8f
internal const val NEW_MEMO_REVEAL_TIMEOUT_MS = 5_000L

internal data class MainScreenDependencies(
    val mainViewModel: MainViewModel,
    val sidebarViewModel: SidebarViewModel,
    val editorViewModel: MemoEditorViewModel,
    val recordingViewModel: RecordingViewModel,
    val conflictViewModel: com.lomo.app.feature.conflict.SyncConflictViewModel,
    val conflictStateViewModel: SyncConflictStateViewModel,
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
    onNavigateToStatistics: () -> Unit,
    onNavigateToShare: (String, Long) -> Unit = { _, _ -> },
    lanShareEnabled: Boolean = true,
    viewModel: MainViewModel = activityHiltViewModel(),
    sidebarViewModel: SidebarViewModel = injectedHiltViewModel(),
    editorViewModel: MemoEditorViewModel = injectedHiltViewModel(),
    recordingViewModel: RecordingViewModel = injectedHiltViewModel(),
    conflictViewModel: com.lomo.app.feature.conflict.SyncConflictViewModel = injectedHiltViewModel(),
    conflictStateViewModel: SyncConflictStateViewModel = injectedHiltViewModel(),
) {
    val dependencies =
        remember(
            viewModel,
            sidebarViewModel,
            editorViewModel,
            recordingViewModel,
            conflictViewModel,
            conflictStateViewModel,
        ) {
            MainScreenDependencies(
                mainViewModel = viewModel,
                sidebarViewModel = sidebarViewModel,
                editorViewModel = editorViewModel,
                recordingViewModel = recordingViewModel,
                conflictViewModel = conflictViewModel,
                conflictStateViewModel = conflictStateViewModel,
            )
        }
    val screenState = collectMainScreenUiSnapshot(dependencies = dependencies)
    val hostState = rememberMainScreenHostState()
    val pagedUiMemos: LazyPagingItems<MemoUiModel> =
        dependencies.mainViewModel.pagedUiMemos.collectAsLazyPagingItems()
    val pagedItemSnapshotList = pagedUiMemos.itemSnapshotList
    val displayedVisibleUiMemoStartIndex = pagedItemSnapshotList.placeholdersBefore
    val displayedVisibleUiMemos =
        remember(pagedItemSnapshotList) {
            pagedItemSnapshotList.items.toImmutableList()
        }
    val renderState =
        remember(screenState, displayedVisibleUiMemos) {
            screenState.copy(
                uiMemos = displayedVisibleUiMemos,
                visibleUiMemos = displayedVisibleUiMemos,
                hasRawItems = displayedVisibleUiMemos.isNotEmpty(),
            )
        }
    val currentListTopMemoId = displayedVisibleUiMemos.firstOrNull()?.memo?.id
    val unknownErrorMessage = stringResource(R.string.error_unknown)
    var isRefreshing by remember { mutableStateOf(false) }

    MainScreenDraftAutosaveEffect(
        editorController = hostState.editorController,
        dependencies = dependencies,
    )
    MainScreenAutomaticRefreshEffect(
        onRequestAutomaticRefresh = dependencies.mainViewModel.requestAutomaticRefreshForVisibleScreen,
        uiState = renderState.uiState,
    )
    MainScreenPendingNewMemoCreationEffect(
        pendingRequest = renderState.pendingNewMemoCreationRequest,
        listState = hostState.listState,
        pagedUiMemos = pagedUiMemos,
        dependencies = dependencies,
    )

    MainScreenTransientEffects(
        dependencies = dependencies,
        visibleUiMemos = displayedVisibleUiMemos,
        visibleUiMemoStartIndex = displayedVisibleUiMemoStartIndex,
        canResolveOffscreenMainListFocus =
            renderState.searchQuery.isBlank() &&
                !renderState.memoListFilter.isActive &&
                !renderState.memoListFilter.hasSortOverride,
        listState = hostState.listState,
        editorController = hostState.editorController,
        directoryGuideController = hostState.directoryGuideController,
        snackbarHostState = hostState.snackbarHostState,
        unknownErrorMessage = unknownErrorMessage,
        canOpenCreateMemo =
            renderState.uiState is MainViewModel.MainScreenState.Ready &&
                renderState.pendingNewMemoCreationRequest == null,
    )
    MainScreenConflictHost(dependencies = dependencies)
    MainScreenContentHost(
        screenState = renderState,
        pagedUiMemos = pagedUiMemos,
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
        onNavigateToStatistics = onNavigateToStatistics,
        onNavigateToShare = onNavigateToShare,
        lanShareEnabled = lanShareEnabled,
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
    pagedUiMemos: LazyPagingItems<MemoUiModel>,
    dependencies: MainScreenDependencies,
) {
    val scope = rememberCoroutineScope()
    val latestDependencies = rememberUpdatedState(dependencies)
    val creationCoordinator =
        remember(listState, scope) {
            NewMemoCreationCoordinator<PendingNewMemoCreationRequest>(
                scope = scope,
                isListAtAbsoluteTop = {
                    listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                },
                scrollListToAbsoluteTop = {
                    listState.animateScrollToItem(0)
                },
                createMemo = { request, _ ->
                    // The feed renders loaded rows from the paging snapshot and only calls
                    // pagedUiMemos[index] for placeholders, so scrolling up to the top never updates
                    // Paging's anchorPosition. Register a top access here so the create-triggered
                    // Room refresh reloads anchored at the top (placeholdersBefore=0) instead of the
                    // stale deep anchor — otherwise the top rows briefly become placeholders during
                    // the refresh, which reads as the whole-list "flash".
                    pagedUiMemos[0]
                    val consumedRequest =
                        latestDependencies.value.mainViewModel.consumePendingNewMemoCreationRequest(request.requestId)
                    if (consumedRequest != null) {
                        latestDependencies.value.editorViewModel.createMemo(
                            consumedRequest.content,
                            geoLocation = consumedRequest.geoLocation,
                            timestampMillis = consumedRequest.timestampMillis,
                        )
                    }
                },
                currentTopMemoId = {
                    pagedUiMemos.itemSnapshotList.items.firstOrNull()?.memo?.id
                },
                awaitNewTopItemAndReveal = { previousTopId ->
                    val newTopId =
                        withTimeoutOrNull(NEW_MEMO_REVEAL_TIMEOUT_MS) {
                            snapshotFlow { pagedUiMemos.itemSnapshotList.items.firstOrNull()?.memo?.id }
                                .first { topId -> topId != null && topId != previousTopId }
                        }
                    if (newTopId != null) {
                        // Pin the freshly-inserted (initially zero-height) row to the viewport top so its
                        // two-phase enter (expand then fade) plays in view instead of above the fold.
                        latestDependencies.value.mainViewModel.enterAnimationRegistry.beginEnter(newTopId)
                        listState.scrollToItem(0)
                    }
                },
            )
        }

    LaunchedEffect(pendingRequest?.requestId) {
        pendingRequest?.let(creationCoordinator::submit)
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
    val mainListTotalCount: Int,
    val sidebarUiState: SidebarViewModel.SidebarUiState,
    val dateFormat: String,
    val timeFormat: String,
    val showInputHints: Boolean,
    val doubleTapEditEnabled: Boolean,
    val freeTextCopyEnabled: Boolean,
    val memoActionAutoReorderEnabled: Boolean,
    val memoActionOrder: ImmutableList<String>,
    val inputToolbarToolOrder: ImmutableList<String>,
    val quickSaveOnBackEnabled: Boolean,
    val scrollbarEnabled: Boolean,
    val shareCardShowTime: Boolean,
    val shareCardShowSignature: Boolean,
    val shareCardSignatureText: String,
    val customFontPath: String?,
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
    val editorController: MemoEditorController,
    val isExpanded: Boolean,
    val directoryGuideController: MainDirectoryGuideController,
)

internal typealias MainScreenInteractionContent =
    @Composable ((MemoMenuSelection) -> Unit, (Memo) -> Unit) -> Unit

internal data class MainScreenInteractionCallbacks(
    val onCreateMemo: (String, String?, Long?) -> Unit,
    val onCameraCaptureError: (Throwable) -> Unit,
    val onStartRecording: () -> Unit,
    val onStopRecording: () -> Unit,
    val onVersionHistory: (MemoMenuSelection) -> Unit,
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
    visibleUiMemoStartIndex: Int,
    canResolveOffscreenMainListFocus: Boolean,
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
            focusMemoInMainScreenWithFallback(
                memoId = memoId,
                visibleUiMemos = visibleUiMemos,
                visibleUiMemoStartIndex = visibleUiMemoStartIndex,
                canResolveOffscreenMainListFocus = canResolveOffscreenMainListFocus,
                resolveOffscreenIndex = dependencies.mainViewModel.resolveDefaultMainListIndex,
                positioner = MainScreenFocusPositioner { index -> listState.scrollToItem(index) },
            )
        },
        focusRetryKey =
            remember(visibleUiMemos, visibleUiMemoStartIndex, listState) {
                derivedStateOf {
                    Triple(
                        visibleUiMemoStartIndex,
                        visibleUiMemos.map { uiMemo -> uiMemo.memo.id },
                        listState.firstVisibleItemIndex,
                    )
                }
            }.value,
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
    shareCardShowSignature: Boolean,
    shareCardSignatureText: String,
    customFontPath: String?,
    dateFormat: String,
    timeFormat: String,
    quickSaveOnBackEnabled: Boolean,
    memoActionAutoReorderEnabled: Boolean,
    memoActionOrder: ImmutableList<String>,
    inputToolbarToolOrder: ImmutableList<String>,
    availableTags: ImmutableList<String>,
    showInputHints: Boolean,
    onNavigateToShare: (String, Long) -> Unit,
    lanShareEnabled: Boolean,
    content: MainScreenInteractionContent,
) {
    val gitSyncEnabled by dependencies.mainViewModel.gitSyncEnabled.collectAsStateWithLifecycle()
    val versionHistoryState by dependencies.mainViewModel.versionHistoryState.collectAsStateWithLifecycle()
    val rootDirectory by dependencies.mainViewModel.rootDirectory.collectAsStateWithLifecycle()
    val imageDirectory by dependencies.mainViewModel.imageDirectory.collectAsStateWithLifecycle()
    val imageMap by dependencies.mainViewModel.imageMap.collectAsStateWithLifecycle()
    val voiceDirectory by dependencies.mainViewModel.voiceDirectory.collectAsStateWithLifecycle()
    val stableImageMap = remember(imageMap) { imageMap.toImmutableMap() }
    val inputHints = rememberInputHints(showInputHints = showInputHints)
    val context = LocalContext.current

    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            val granted = permissions.values.any { it }
            if (granted) {
                appendLastKnownLocation(context, editorController::appendMarkdownBlock)
            }
        }

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
    val memoMenuCommandHandler =
        rememberMemoMenuCommandHandler(
            presentationState =
                MemoMenuPresentationState(
                    shareCardShowTime = shareCardShowTime,
                    shareCardShowSignature = shareCardShowSignature,
                    shareCardSignatureText = shareCardSignatureText,
                    customFontPath = customFontPath,
                    showVersionHistory = true,
                    memoActionAutoReorderEnabled = memoActionAutoReorderEnabled,
                    memoActionOrder = memoActionOrder,
                ),
            onEditMemo = editorController::openForEdit,
            onDeleteMemo = dependencies.mainViewModel.deleteMemo,
            onLanShare =
                if (lanShareEnabled) {
                    { request -> onNavigateToShare(request.content, request.timestamp) }
                } else {
                    null
                },
            onTogglePin = dependencies.mainViewModel.setMemoPinned,
            onVersionHistory = interactionCallbacks.onVersionHistory,
            onMemoActionInvoked = dependencies.mainViewModel.recordMemoActionUsage,
            onMemoActionOrderChanged = dependencies.mainViewModel.updateMemoActionOrder,
        )

    val isRecording by dependencies.recordingViewModel.isRecording.collectAsStateWithLifecycle()
    val onAttachLocation =
        mainMemoAttachLocationCommand(
            context = context,
            onPermissionRequired = { locationPermissionLauncher.launch(mainMemoLocationPermissions()) },
            onLocationMarkdown = editorController::appendMarkdownBlock,
        )
    val editorSurface =
        rememberMainMemoEditorSurface(
            dependencies = dependencies,
            interactionCallbacks = interactionCallbacks,
            imageDirectory = imageDirectory,
            rootDirectory = rootDirectory,
            imageMap = stableImageMap,
            availableTags = availableTags,
            inputHints = inputHints,
            dateFormat = dateFormat,
            timeFormat = timeFormat,
            quickSaveOnBackEnabled = quickSaveOnBackEnabled,
            inputToolbarToolOrder = inputToolbarToolOrder,
            isRecording = isRecording,
            onImageDirectoryMissing = directoryGuideController::requestImage,
            onAttachLocation = onAttachLocation,
        )

    MemoInteractionHost(
        menuCommandHandler = memoMenuCommandHandler,
        controller = editorController,
        editorSurface = editorSurface,
    ) { showMenu, openEditor ->
        content(showMenu, openEditor)
    }

    VersionHistoryOverlay(
        state = versionHistoryState,
        rootPath = rootDirectory,
        imagePath = imageDirectory,
        imageMap = stableImageMap,
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
            onCreateMemo = { contentText, geoLocation, timestampMillis ->
                dependencies.mainViewModel.requestPendingNewMemoCreation(
                    content = contentText,
                    geoLocation = geoLocation,
                    timestampMillis = timestampMillis,
                )
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
                dependencies.mainViewModel.loadVersionHistory(state.memo)
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
