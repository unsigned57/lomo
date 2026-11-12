package com.lomo.app.feature.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.lomo.app.R
import com.lomo.app.feature.common.PendingUiEvent
import com.lomo.app.feature.conflict.SyncConflictStateViewModel
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.memo.MemoEditorController
import com.lomo.app.feature.memo.MemoEditorSubmissionId
import com.lomo.app.feature.memo.MemoEditorViewModel
import com.lomo.app.feature.memo.MemoInteractionHost
import com.lomo.app.feature.memo.MemoMenuContextAction
import com.lomo.app.feature.memo.MemoMenuPresentationState
import com.lomo.app.feature.memo.MemoMenuSelection
import com.lomo.app.feature.memo.appendImageMarkdown
import com.lomo.app.feature.memo.appendMarkdownBlock
import com.lomo.app.feature.memo.captureMarkdownInsertion
import com.lomo.app.feature.memo.rememberFullMemoEditorOpener
import com.lomo.app.feature.memo.rememberMemoMenuCommandHandler
import com.lomo.app.util.activityKoinViewModel
import com.lomo.app.util.injectedKoinViewModel
import com.lomo.domain.model.CalendarHeatmapThresholds
import com.lomo.domain.model.ImageLocationSnapshot
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.QuickBallConfig
import com.lomo.domain.model.RecoveryDiagnosticReport
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

internal const val DRAFT_AUTOSAVE_DEBOUNCE_MILLIS = 500L
internal const val MAIN_SCREEN_LIST_SCROLL_SETTLE_INDEX = 10

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
    foregroundEntryId: Long = 0L,
    viewModel: MainViewModel = activityKoinViewModel(),
    sidebarViewModel: SidebarViewModel = injectedKoinViewModel(),
    editorViewModel: MemoEditorViewModel = injectedKoinViewModel(),
    recordingViewModel: RecordingViewModel = injectedKoinViewModel(),
    conflictViewModel: com.lomo.app.feature.conflict.SyncConflictViewModel = injectedKoinViewModel(),
    conflictStateViewModel: SyncConflictStateViewModel = injectedKoinViewModel(),
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
    RecoveryDiagnosticExportEffect(
        viewModel = dependencies.mainViewModel,
        snackbarHostState = hostState.snackbarHostState,
    )
    val pagedUiMemos: LazyPagingItems<MemoUiModel> =
        dependencies.mainViewModel.pagedUiMemos.collectAsLazyPagingItems()
    val pagedItemSnapshotList = pagedUiMemos.itemSnapshotList
    val displayedVisibleUiMemoStartIndex = pagedItemSnapshotList.placeholdersBefore
    val displayedVisibleUiMemos = pagedItemSnapshotList.items
    val unknownErrorMessage = stringResource(R.string.error_unknown)
    val memoNotFoundMessage = stringResource(R.string.main_list_focus_memo_missing)
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    MainScreenDraftAutosaveEffect(
        editorController = hostState.editorController,
        dependencies = dependencies,
    )
    MainScreenPendingNewMemoCreationEffect(
        listState = hostState.listState,
        pagedUiMemos = pagedUiMemos,
        dependencies = dependencies,
    )

    MainScreenTransientEffects(
        dependencies = dependencies,
        visibleUiMemos = displayedVisibleUiMemos,
        visibleUiMemoStartIndex = displayedVisibleUiMemoStartIndex,
        searchQuery = screenState.searchQuery,
        memoListFilter = screenState.memoListFilter,
        canResolveOffscreenMainListFocus =
        screenState.searchQuery.isBlank() &&
            !screenState.memoListFilter.isActive &&
            !screenState.memoListFilter.hasSortOverride,
        listState = hostState.listState,
        editorController = hostState.editorController,
        directoryGuideController = hostState.directoryGuideController,
        snackbarHostState = hostState.snackbarHostState,
        unknownErrorMessage = unknownErrorMessage,
        memoNotFoundMessage = memoNotFoundMessage,
        canOpenCreateMemo =
        screenState.uiState is MainViewModel.MainScreenState.Ready &&
            screenState.pendingNewMemoCreationEvent == null,
        foregroundEntryId = foregroundEntryId,
        autoOpenInputOnForeground = screenState.autoOpenInputOnForeground,
        pendingNewMemoCreationEvent = screenState.pendingNewMemoCreationEvent,
    )
    MainScreenConflictHost(dependencies = dependencies)
    MainScreenFullyDrawnEffect(pagedUiMemos = pagedUiMemos)
    MainScreenContentHost(
        screenState = screenState,
        pagedUiMemos = pagedUiMemos,
        hostState = hostState,
        dependencies = dependencies,
        unknownErrorMessage = unknownErrorMessage,
        isRefreshing = isRefreshing,
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
private fun RecoveryDiagnosticExportEffect(viewModel: MainViewModel, snackbarHostState: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dispatcherProvider =
        org.koin.compose.koinInject<com.lomo.domain.usecase.DispatcherProvider>()
    val savedMessage = stringResource(R.string.engine_recovery_diagnostics_saved)
    val failedMessage = stringResource(R.string.engine_recovery_diagnostics_failed)
    var pendingReport by remember { mutableStateOf<RecoveryDiagnosticReport?>(null) }
    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri ->
            val report = pendingReport
            pendingReport = null
            if (uri != null && report != null) {
                scope.launch {
                    try {
                        withContext(dispatcherProvider.io) {
                            val output =
                                checkNotNull(context.contentResolver.openOutputStream(uri)) {
                                    "Document provider did not open the diagnostic destination"
                                }
                            output.bufferedWriter(Charsets.UTF_8).use { writer ->
                                writer.write(report.content)
                            }
                        }
                        snackbarHostState.showSnackbar(savedMessage)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Timber.w(error, "Recovery diagnostic destination write failed")
                        snackbarHostState.showSnackbar(failedMessage)
                    }
                }
            }
        }

    val diagnosticExports by viewModel.diagnosticExports.collectAsStateWithLifecycle()
    LaunchedEffect(diagnosticExports, launcher) {
        val event = diagnosticExports.lastOrNull() ?: return@LaunchedEffect
        pendingReport = event.payload
        launcher.launch(event.payload.fileName)
        viewModel.consumeDiagnosticExport(event.id)
    }
}

@Composable
private fun MainScreenPendingNewMemoCreationEffect(
    listState: androidx.compose.foundation.lazy.LazyListState,
    pagedUiMemos: LazyPagingItems<MemoUiModel>,
    dependencies: MainScreenDependencies,
) {
    val scope = rememberCoroutineScope()
    val latestDependencies = rememberUpdatedState(dependencies)
    val creationCoordinator =
        remember(listState, scope) {
            NewMemoCreationCoordinator<PendingUiEvent<PendingNewMemoCreationRequest>>(
                NewMemoCreationCoordinatorDependencies<PendingUiEvent<PendingNewMemoCreationRequest>>(
                    scope = scope,
                    isListAtAbsoluteTop = {
                        listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                    },
                    scrollListToAbsoluteTop = {
                        listState.animateScrollToItem(0)
                    },
                    readBaselineKeys = {
                        pagedUiMemos.itemSnapshotList.items.mapTo(mutableSetOf()) { it.memo.id }
                    },
                    prepareEnter = { keys ->
                        latestDependencies.value.mainViewModel.enterAnimationRegistry.beginPendingEnter(keys)
                    },
                    bindCommittedEnter = { requestId, memoId ->
                        latestDependencies.value.mainViewModel.enterAnimationRegistry
                            .bindPendingEnter(requestId, memoId)
                    },
                    startPreparedEnter = { requestId ->
                        latestDependencies.value.mainViewModel.enterAnimationRegistry.startPendingEnter(requestId)
                    },
                    createdMemoRank = { memoId ->
                        latestDependencies.value.mainViewModel.rankInActiveMainListQuery(memoId)
                    },
                    createMemo = { event, _, onAccepted ->
                        // The feed renders loaded rows from the paging snapshot and only calls
                        // pagedUiMemos[index] for placeholders, so scrolling up to the top never updates
                        // Paging's anchorPosition. Register a top access here so the create-triggered
                        // Rust commit publication invalidates this source. Anchor that refresh at
                        // the top (placeholdersBefore=0) instead of the stale deep position; otherwise
                        // the top rows briefly become placeholders and the whole list appears to flash.
                        if (pagedUiMemos.itemCount > 0) {
                            pagedUiMemos[0]
                        }
                        val consumedRequest =
                            latestDependencies.value.mainViewModel.consumePendingNewMemoCreationEvent(event.id)
                        if (consumedRequest == null) {
                            latestDependencies.value.editorViewModel.submissions.reject(
                                event.payload.submissionId,
                                IllegalStateException("Pending memo request was already consumed or cancelled"),
                            )
                            null
                        } else {
                            try {
                                latestDependencies.value.editorViewModel.submissions.create(
                                    submissionId = consumedRequest.submissionId,
                                    content = consumedRequest.content,
                                    timestampMillis = consumedRequest.timestampMillis,
                                    onAccepted = onAccepted,
                                )
                                if (
                                    latestDependencies.value.editorViewModel.submissions.await(
                                        consumedRequest.submissionId,
                                    )
                                ) {
                                    latestDependencies.value.editorViewModel.submissions
                                        .committedMemo(consumedRequest.submissionId)?.id
                                } else {
                                    null
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                latestDependencies.value.editorViewModel.submissions.reject(
                                    consumedRequest.submissionId,
                                    error,
                                )
                                // behavior-contract: silent-result-ok: create failure is published on
                                // the editor submission machine; null only stops waiting for a new head
                                null
                            }
                        }
                    },
                    awaitCreatedItem = { memoId, rank -> pagedUiMemos.awaitCreatedMemo(memoId, rank) },
                    revealCreatedItem = { memoId, rank ->
                        listState.positionCreatedMemoForEnter(pagedUiMemos, memoId, rank)
                    },
                    releaseEnter = { memoId ->
                        latestDependencies.value.mainViewModel.enterAnimationRegistry.settleEnter(memoId)
                    },
                    cancelPreparedEnter = { requestId ->
                        latestDependencies.value.mainViewModel.enterAnimationRegistry.cancelEnterRequest(requestId)
                    },
                ),
            )
        }

    val lifecycleOwner = LocalLifecycleOwner.current
    val requests = dependencies.mainViewModel.pendingNewMemoCreationEvents
    val activeRequests = remember(requests, lifecycleOwner) {
        requests.flowWithLifecycle(lifecycleOwner.lifecycle)
    }
    NewMemoCreationRequestEffect(activeRequests) { event ->
        if (!creationCoordinator.submit(event)) {
            latestDependencies.value.mainViewModel.cancelPendingNewMemoCreationEvent(event.id)
            latestDependencies.value.editorViewModel.submissions.reject(
                event.payload.submissionId,
                IllegalStateException("Memo creation reveal is still in progress"),
            )
        }
    }
}

internal data class DraftAutosaveState(val editingMemoId: String?, val text: String, val isVisible: Boolean)

internal data class MainScreenUiSnapshot(
    val searchQuery: String,
    val memoListFilter: MemoListFilter,
    val sidebarUiState: SidebarViewModel.SidebarUiState,
    val dateFormat: String,
    val timeFormat: String,
    val calendarHeatmapThresholds: CalendarHeatmapThresholds,
    val showInputHints: Boolean,
    val doubleTapEditEnabled: Boolean,
    val freeTextCopyEnabled: Boolean,
    val autoOpenInputOnForeground: Boolean,
    val inputToolbarToolOrder: ImmutableList<String>,
    val quickSaveOnBackEnabled: Boolean,
    val scrollbarEnabled: Boolean,
    val quickBallConfig: QuickBallConfig,
    val shareCardShowTime: Boolean,
    val shareCardShowSignature: Boolean,
    val shareCardSignatureText: String,
    val customFontPath: String?,
    val uiState: MainViewModel.MainScreenState,
    val pendingNewMemoCreationEvent: PendingUiEvent<PendingNewMemoCreationRequest>?,
)

@OptIn(ExperimentalMaterial3Api::class)
internal data class MainScreenHostState(
    val sidebar: MainSidebarState,
    val scope: CoroutineScope,
    val snackbarHostState: SnackbarHostState,
    val scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    val listState: androidx.compose.foundation.lazy.LazyListState,
    val editorController: MemoEditorController,
    val directoryGuideController: MainDirectoryGuideController,
)

internal typealias MainScreenInteractionContent =
    @Composable ((MemoMenuSelection) -> Unit, (Memo) -> Unit) -> Unit

internal data class MainScreenInteractionCallbacks(
    val onCreateMemo: (MemoEditorSubmissionId, String, Long?) -> Boolean,
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
    visibleUiMemos: List<MemoUiModel>,
    visibleUiMemoStartIndex: Int,
    searchQuery: String,
    memoListFilter: MemoListFilter,
    canResolveOffscreenMainListFocus: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    editorController: MemoEditorController,
    directoryGuideController: MainDirectoryGuideController,
    snackbarHostState: SnackbarHostState,
    unknownErrorMessage: String,
    memoNotFoundMessage: String,
    canOpenCreateMemo: Boolean,
    foregroundEntryId: Long,
    autoOpenInputOnForeground: Boolean,
    pendingNewMemoCreationEvent: PendingUiEvent<PendingNewMemoCreationRequest>?,
) {
    // Two leaf scopes collect their own StateFlows so a change in one surface does not recompose
    // the other. draftText is deliberately passed as the StateFlow itself: it is read inside the
    // effects when a decision runs, and must never become a composition or LaunchedEffect key —
    // per-keystroke recomposition of this host previously restarted external command execution.
    MainScreenForegroundEffects(
        dependencies = dependencies,
        foregroundEntryId = foregroundEntryId,
        autoOpenInputOnForeground = autoOpenInputOnForeground,
        canOpenCreateMemo = canOpenCreateMemo,
        pendingNewMemoCreationEvent = pendingNewMemoCreationEvent,
        directoryGuideController = directoryGuideController,
        editorController = editorController,
    )

    MainScreenEventEffectsSurface(
        dependencies = dependencies,
        visibleUiMemos = visibleUiMemos,
        visibleUiMemoStartIndex = visibleUiMemoStartIndex,
        searchQuery = searchQuery,
        memoListFilter = memoListFilter,
        canResolveOffscreenMainListFocus = canResolveOffscreenMainListFocus,
        listState = listState,
        editorController = editorController,
        directoryGuideController = directoryGuideController,
        snackbarHostState = snackbarHostState,
        unknownErrorMessage = unknownErrorMessage,
        memoNotFoundMessage = memoNotFoundMessage,
    )
}

@Composable
private fun MainScreenEventEffectsSurface(
    dependencies: MainScreenDependencies,
    visibleUiMemos: List<MemoUiModel>,
    visibleUiMemoStartIndex: Int,
    searchQuery: String,
    memoListFilter: MemoListFilter,
    canResolveOffscreenMainListFocus: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    editorController: MemoEditorController,
    directoryGuideController: MainDirectoryGuideController,
    snackbarHostState: SnackbarHostState,
    unknownErrorMessage: String,
    memoNotFoundMessage: String,
) {
    val errorMessage by dependencies.mainViewModel.errorMessage.collectAsStateWithLifecycle()
    val imageLocationState by dependencies.mainViewModel.imageLocationState.collectAsStateWithLifecycle()
    val imageLocationError = when (imageLocationState) {
        is ImageLocationSnapshot.Unavailable -> stringResource(R.string.image_directory_scan_failed)
        ImageLocationSnapshot.Loading, is ImageLocationSnapshot.Ready -> null
    }
    val editorErrorMessage by dependencies.editorViewModel.errorMessage.collectAsStateWithLifecycle()
    val recordingErrorMessage by dependencies.recordingViewModel.errorMessage.collectAsStateWithLifecycle()
    val sharedContentEvents by dependencies.mainViewModel.sharedContentEvents.collectAsStateWithLifecycle()
    val pendingSharedImageEvents by dependencies.mainViewModel.pendingSharedImageEvents.collectAsStateWithLifecycle()
    val appActionEvents by dependencies.mainViewModel.appActionEvents.collectAsStateWithLifecycle()
    val imageDirectory by dependencies.mainViewModel.imageDirectory.collectAsStateWithLifecycle()
    val openFullMemoEditor =
        rememberFullMemoEditorOpener(editorController, dependencies.editorViewModel::reportError)

    MainScreenEventEffectsHost(
        sharedContentEvents = remember(sharedContentEvents) { sharedContentEvents.toImmutableList() },
        appActionEvents = remember(appActionEvents) { appActionEvents.toImmutableList() },
        pendingSharedImageEvents = remember(pendingSharedImageEvents) { pendingSharedImageEvents.toImmutableList() },
        imageDirectory = imageDirectory,
        errorMessage = errorMessage ?: imageLocationError,
        editorErrorMessage = editorErrorMessage,
        recordingErrorMessage = recordingErrorMessage,
        snackbarHostState = snackbarHostState,
        unknownErrorMessage = unknownErrorMessage,
        memoNotFoundMessage = memoNotFoundMessage,
        onAppendMarkdown = editorController::appendMarkdownBlock,
        onAppendImageMarkdown = editorController::appendImageMarkdown,
        onEnsureEditorVisible = editorController::ensureVisible,
        onOpenEditMemo = openFullMemoEditor,
        onFocusMemoInList = { memoId ->
            focusMemoInMainScreenWithFallback(
                memoId = memoId,
                visibleUiMemos = visibleUiMemos,
                visibleUiMemoStartIndex = visibleUiMemoStartIndex,
                canResolveOffscreenMainListFocus = canResolveOffscreenMainListFocus,
                resolveOffscreenIndex = dependencies.mainViewModel.resolveDefaultMainListIndex,
                positioner =
                MainScreenFocusPositioner { index ->
                    // Same pending-modification constraint as the new-memo reveal: a
                    // synchronous scrollToItem can force subcomposition before the
                    // recomposer applies pending list mutations and crash the process.
                    listState.requestScrollToItem(index)
                },
            )
        },
        onMainListFocusConsumed = dependencies.mainViewModel::clearMainListFocusReanchor,
        focusRetryKey =
        remember(
            visibleUiMemos,
            visibleUiMemoStartIndex,
            searchQuery,
            memoListFilter,
            appActionEvents,
        ) {
            resolveMainListFocusRetryKey(
                searchQuery = searchQuery,
                filter = memoListFilter,
                windowStartIndex = visibleUiMemoStartIndex,
                visibleMemos = visibleUiMemos,
                pendingFocusMemoIds =
                appActionEvents
                    .mapNotNullTo(mutableSetOf()) { event ->
                        (event.payload as? MainViewModel.AppAction.FocusMemo)?.memoId
                    },
            )
        },
        onResolveMemoById = dependencies.mainViewModel.resolveMemoById,
        onSaveImage = { uri, onResult, onError ->
            dependencies.editorViewModel.saveImage(
                uri = uri,
                draftId = dependencies.editorViewModel.ownerDraftId,
                onResult = onResult,
                onError = onError,
            )
        },
        onRequireImageDirectory = directoryGuideController::requestImage,
        onConsumeSharedContentEvent = dependencies.mainViewModel.consumeSharedContentEvent,
        onConsumeAppActionEvent = dependencies.mainViewModel.consumeAppActionEvent,
        onConsumePendingSharedImageEvent = dependencies.mainViewModel.consumePendingSharedImageEvent,
        onClearMainError = dependencies.mainViewModel.clearError,
        onClearEditorError = dependencies.editorViewModel::clearError,
        onClearRecordingError = dependencies.recordingViewModel::clearError,
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
    inputToolbarToolOrder: ImmutableList<String>,
    availableTags: ImmutableList<String>,
    showInputHints: Boolean,
    onNavigateToShare: (String, Long) -> Unit,
    lanShareEnabled: Boolean,
    content: MainScreenInteractionContent,
) {
    val versionHistoryState by dependencies.mainViewModel.versionHistoryState.collectAsStateWithLifecycle()
    val rootDirectory by dependencies.mainViewModel.rootDirectory.collectAsStateWithLifecycle()
    val imageDirectory by dependencies.mainViewModel.imageDirectory.collectAsStateWithLifecycle()
    val imageMap by dependencies.mainViewModel.imageMap.collectAsStateWithLifecycle()
    val voiceDirectory by dependencies.mainViewModel.voiceDirectory.collectAsStateWithLifecycle()
    val stableImageMap = remember(imageMap) { imageMap.toImmutableMap() }
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
    val openFullMemoEditor =
        rememberFullMemoEditorOpener(editorController, dependencies.editorViewModel::reportError)
    val memoMenuCommandHandler =
        rememberMemoMenuCommandHandler(
            presentationState =
            MemoMenuPresentationState(
                shareCardShowTime = shareCardShowTime,
                shareCardShowSignature = shareCardShowSignature,
                shareCardSignatureText = shareCardSignatureText,
                customFontPath = customFontPath,
            ),
            onEditMemo = openFullMemoEditor,
            onDeleteMemo = dependencies.mainViewModel.deleteMemo,
            onLanShare =
            if (lanShareEnabled) {
                { request -> onNavigateToShare(request.content, request.timestamp) }
            } else {
                null
            },
            contextAction = MemoMenuContextAction.TogglePin(dependencies.mainViewModel.setMemoPinned),
            onVersionHistory = interactionCallbacks.onVersionHistory,
        )

    val isRecording by dependencies.recordingViewModel.isRecording.collectAsStateWithLifecycle()
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
        )

    MemoInteractionHost(
        menuCommandHandler = memoMenuCommandHandler,
        controller = editorController,
        editorSurface = editorSurface,
        onEditorOpenFailure = dependencies.editorViewModel::reportError,
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
): MainScreenInteractionCallbacks = remember(
    dependencies,
    editorController,
    directoryGuideController,
    voiceDirectory,
    scope,
    snackbarHostState,
    unknownErrorMessage,
) {
    MainScreenInteractionCallbacks(
        onCreateMemo = { submissionId, contentText, timestampMillis ->
            dependencies.mainViewModel.requestPendingNewMemoCreation(
                submissionId = submissionId,
                content = contentText,
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
                dependencies.recordingViewModel.startRecording(
                    editorController.editingSession?.draftId ?: dependencies.editorViewModel.ownerDraftId,
                )
            }
        },
        onStopRecording = {
            val insertRecording = editorController.captureMarkdownInsertion()
            dependencies.recordingViewModel.stopRecording { markdown ->
                markdown?.let { block ->
                    insertRecording(block)
                    dependencies.editorViewModel.trackVoiceMarkdown(block)
                }
            }
        },
        onVersionHistory = { state ->
            dependencies.mainViewModel.loadVersionHistory(state.memo)
        },
    )
}
