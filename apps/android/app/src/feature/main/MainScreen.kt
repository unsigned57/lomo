package com.lomo.app.feature.main

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.lomo.app.R
import com.lomo.app.feature.common.PendingUiEvent
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.memo.MemoEditorController
import com.lomo.app.feature.memo.MemoEditorSubmissionId
import com.lomo.app.feature.memo.MemoEditorViewModel
import com.lomo.app.feature.memo.MemoInteractionHost
import com.lomo.app.feature.memo.MemoMenuPresentationState
import com.lomo.app.feature.memo.MemoVersionHistoryUiMapper
import com.lomo.app.feature.memo.appendImageMarkdown
import com.lomo.app.feature.memo.appendMarkdownBlock
import com.lomo.app.feature.memo.openForEdit
import com.lomo.app.feature.memo.rememberMemoMenuCommandHandler
import com.lomo.app.feature.memo.rememberFullMemoEditorOpener
import com.lomo.app.feature.conflict.SyncConflictStateViewModel
import com.lomo.app.util.activityKoinViewModel
import com.lomo.app.util.injectedKoinViewModel
import com.lomo.domain.model.CalendarHeatmapThresholds
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.RecoveryDiagnosticReport
import com.lomo.app.feature.memo.MemoMenuSelection
import com.lomo.ui.component.common.HeadEnterBaseline
import com.lomo.ui.theme.MotionTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

internal const val DRAFT_AUTOSAVE_DEBOUNCE_MILLIS = 500L
internal const val MAIN_SCREEN_LIST_SCROLL_SETTLE_INDEX = 10
internal const val MAIN_SCREEN_FAB_VISIBILITY_THRESHOLD = 0.9f
internal const val MAIN_SCREEN_MODAL_DRAWER_WIDTH_FRACTION = 0.85f
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
    onNavigateToTasks: () -> Unit,
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
        pendingRequestEvent = screenState.pendingNewMemoCreationEvent,
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
        onNavigateToTasks = onNavigateToTasks,
        onNavigateToStatistics = onNavigateToStatistics,
        onNavigateToShare = onNavigateToShare,
        lanShareEnabled = lanShareEnabled,
    )
}

@Composable
private fun RecoveryDiagnosticExportEffect(
    viewModel: MainViewModel,
    snackbarHostState: SnackbarHostState,
) {
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
    pendingRequestEvent: PendingUiEvent<PendingNewMemoCreationRequest>?,
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
                    readTopBaseline = {
                        pagedUiMemos.resolveHeadEnterBaseline()
                    },
                    prepareNewTopEnter = { baseline ->
                        latestDependencies.value.mainViewModel.enterAnimationRegistry
                            .beginPendingHeadEnter(baseline)
                    },
                    newHeadRank = { memoId ->
                        latestDependencies.value.mainViewModel.rankInActiveMainListQuery(memoId)
                    },
                    createMemo = { event, _ ->
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
                    awaitNewTopItem = { baseline ->
                        withTimeoutOrNull(NEW_MEMO_REVEAL_TIMEOUT_MS) {
                            snapshotFlow { pagedUiMemos.itemSnapshotList.items.firstOrNull()?.let { it.memo.id } }
                                .first { topId -> topId != null && baseline.isResolvedByHeadId(topId) }
                        }
                    },
                    revealNewTopItem = {
                        // Pin the freshly-inserted row to the viewport top so its two-phase enter
                        // (expand then fade) plays in view instead of above the fold.
                        listState.scrollToItem(0)
                    },
                    cancelPreparedEnter = { requestId ->
                        latestDependencies.value.mainViewModel.enterAnimationRegistry.cancelEnterRequest(requestId)
                    },
                ),
            )
        }

    LaunchedEffect(pendingRequestEvent?.id) {
        pendingRequestEvent?.let { event ->
            if (!creationCoordinator.submit(event)) {
                latestDependencies.value.mainViewModel.cancelPendingNewMemoCreationEvent(event.id)
                latestDependencies.value.editorViewModel.submissions.reject(
                    event.payload.submissionId,
                    IllegalStateException("Memo creation reveal is still in progress"),
                )
            }
        }
    }
}

private fun LazyPagingItems<MemoUiModel>.resolveHeadEnterBaseline(): HeadEnterBaseline? {
    val snapshot = itemSnapshotList
    val firstLoadedHeadId = snapshot.items.firstOrNull()?.let { it.memo.id }
    return when {
        snapshot.placeholdersBefore == 0 && firstLoadedHeadId != null ->
            HeadEnterBaseline.ExistingHead(firstLoadedHeadId)

        itemCount == 0 && loadState.refresh is LoadState.NotLoading ->
            HeadEnterBaseline.EmptyList

        else -> null
    }
}

internal data class DraftAutosaveState(
    val editingMemoId: String?,
    val text: String,
    val isVisible: Boolean,
)

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
    val memoActionAutoReorderEnabled: Boolean,
    val autoOpenInputOnForeground: Boolean,
    val memoActionOrder: ImmutableList<String>,
    val inputToolbarToolOrder: ImmutableList<String>,
    val quickSaveOnBackEnabled: Boolean,
    val scrollbarEnabled: Boolean,
    val shareCardShowTime: Boolean,
    val shareCardShowSignature: Boolean,
    val shareCardSignatureText: String,
    val customFontPath: String?,
    val uiState: MainViewModel.MainScreenState,
    val pendingNewMemoCreationEvent: PendingUiEvent<PendingNewMemoCreationRequest>?,
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
    val errorMessage by dependencies.mainViewModel.errorMessage.collectAsStateWithLifecycle()
    val editorErrorMessage by dependencies.editorViewModel.errorMessage.collectAsStateWithLifecycle()
    val recordingErrorMessage by dependencies.recordingViewModel.errorMessage.collectAsStateWithLifecycle()
    val uiState by dependencies.mainViewModel.uiState.collectAsStateWithLifecycle()
    val sharedContentEvents by dependencies.mainViewModel.sharedContentEvents.collectAsStateWithLifecycle()
    val pendingSharedImageEvents by dependencies.mainViewModel.pendingSharedImageEvents.collectAsStateWithLifecycle()
    val appActionEvents by dependencies.mainViewModel.appActionEvents.collectAsStateWithLifecycle()
    val externalAppCommands by dependencies.mainViewModel.externalAppCommands.collectAsStateWithLifecycle()
    val imageDirectory by dependencies.mainViewModel.imageDirectory.collectAsStateWithLifecycle()
    val voiceDirectory by dependencies.mainViewModel.voiceDirectory.collectAsStateWithLifecycle()
    val draftText by dependencies.editorViewModel.draftText.collectAsStateWithLifecycle()
    val isRecording by dependencies.recordingViewModel.isRecording.collectAsStateWithLifecycle()
    val recordingCaptureId by dependencies.recordingViewModel.recordingCaptureId.collectAsStateWithLifecycle()

    MainScreenForegroundAutoInputEffect(
        foregroundEntryId = foregroundEntryId,
        enabled = autoOpenInputOnForeground,
        uiState = uiState,
        explicitEntryPending =
            sharedContentEvents.isNotEmpty() ||
                pendingSharedImageEvents.isNotEmpty() ||
                appActionEvents.isNotEmpty() ||
                externalAppCommands.isNotEmpty(),
        editorController = editorController,
        isRecording = isRecording,
        hasPendingNewMemoCreation = pendingNewMemoCreationEvent != null,
        draftText = draftText,
    )

    MainScreenExternalAppCommandEffects(
        dependencies = dependencies,
        externalAppCommands = remember(externalAppCommands) { externalAppCommands.toImmutableList() },
        uiState = uiState,
        voiceDirectoryConfigured = voiceDirectory != null,
        canOpenCreateMemo = canOpenCreateMemo,
        isRecording = isRecording,
        recordingCaptureId = recordingCaptureId,
        draftText = draftText,
        directoryGuideController = directoryGuideController,
        editorController = editorController,
    )

    MainScreenEventEffectsHost(
        sharedContentEvents = remember(sharedContentEvents) { sharedContentEvents.toImmutableList() },
        appActionEvents = remember(appActionEvents) { appActionEvents.toImmutableList() },
        pendingSharedImageEvents = remember(pendingSharedImageEvents) { pendingSharedImageEvents.toImmutableList() },
        imageDirectory = imageDirectory,
        errorMessage = errorMessage,
        editorErrorMessage = editorErrorMessage,
        recordingErrorMessage = recordingErrorMessage,
        snackbarHostState = snackbarHostState,
        unknownErrorMessage = unknownErrorMessage,
        memoNotFoundMessage = memoNotFoundMessage,
        onAppendMarkdown = editorController::appendMarkdownBlock,
        onAppendImageMarkdown = editorController::appendImageMarkdown,
        onEnsureEditorVisible = editorController::ensureVisible,
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
            dependencies.editorViewModel.saveImage(uri = uri, onResult = onResult, onError = onError)
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
    memoActionAutoReorderEnabled: Boolean,
    memoActionOrder: ImmutableList<String>,
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
    val openFullMemoEditor = rememberFullMemoEditorOpener(editorController)
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
            onEditMemo = openFullMemoEditor,
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
                    dependencies.recordingViewModel.startRecording()
                }
            },
            onStopRecording = {
                dependencies.recordingViewModel.stopRecording { markdown ->
                    markdown?.let { block ->
                        editorController.appendMarkdownBlock(block)
                        dependencies.editorViewModel.trackVoiceMarkdown(block)
                    }
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
    val markdownWorkspaceRepository =
        org.koin.compose.koinInject<com.lomo.domain.repository.MarkdownWorkspaceRepository>()
    val dispatcherProvider =
        org.koin.compose.koinInject<com.lomo.domain.usecase.DispatcherProvider>()
    val mapper = remember(markdownWorkspaceRepository) { MemoVersionHistoryUiMapper(markdownWorkspaceRepository) }
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
                    withContext(dispatcherProvider.default) {
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
