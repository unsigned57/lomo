package com.lomo.app.feature.main

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import com.lomo.app.feature.common.toUserMessage
import com.lomo.app.feature.conflict.SyncConflictStateViewModel
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.memo.MemoEditorController
import com.lomo.app.feature.memo.MemoEditorHostBindings
import com.lomo.app.feature.memo.MemoEditorSubmissionId
import com.lomo.app.feature.memo.MemoEditorViewModel
import com.lomo.app.feature.memo.MemoInteractionHost
import com.lomo.app.feature.memo.MemoMenuContextAction
import com.lomo.app.feature.memo.MemoMenuLanShareRequest
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
import com.lomo.domain.model.MemoRevision
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

@Composable
internal fun MainScreen(
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
    pagedUiMemos: LazyPagingItems<MemoUiModel>? = null,
    expandCoordinator: MemoListExpandCoordinator? = null,
) {
    val mainBindings = viewModel.hostBindings
    val screenState =
        collectMainScreenUiSnapshot(
            sidebarBindings = sidebarViewModel.hostBindings,
            mainFlows = mainBindings.snapshotFlows,
        )
    val hostState = rememberMainScreenHostState()
    RecoveryDiagnosticExportEffect(
        diagnosticBindings = mainBindings.diagnosticExport,
        snackbarHostState = hostState.snackbarHostState,
    )
    val resolvedPagedUiMemos: LazyPagingItems<MemoUiModel> =
        pagedUiMemos ?: viewModel.pagedUiMemos.collectAsLazyPagingItems()
    val unknownErrorMessage = stringResource(R.string.error_unknown)
    val memoNotFoundMessage = stringResource(R.string.main_list_focus_memo_missing)
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    MainScreenDraftAutosaveEffect(
        editorController = hostState.editorController,
        onSaveDraft = editorViewModel.hostBindings.actions.onSaveDraft,
    )
    MainScreenPendingNewMemoCreationEffect(
        listState = hostState.listState,
        pagedUiMemos = resolvedPagedUiMemos,
        creationBindings = mainBindings.creation,
        editorBindings = editorViewModel.hostBindings,
    )

    val onRequireMediaDirectory: () -> Unit =
        remember(hostState.editorController, onNavigateToSettings) {
            {
                hostState.editorController.close()
                onNavigateToSettings()
            }
        }
    MainScreenTransientEffects(
        effectBindings = mainBindings.effects,
        editorBindings = editorViewModel.hostBindings,
        recordingBindings = recordingViewModel.hostBindings,
        pagedUiMemos = resolvedPagedUiMemos,
        searchQuery = screenState.searchQuery,
        memoListFilter = screenState.memoListFilter,
        canResolveOffscreenMainListFocus =
        screenState.searchQuery.isBlank() &&
            !screenState.memoListFilter.isActive &&
            !screenState.memoListFilter.hasSortOverride,
        listState = hostState.listState,
        editorController = hostState.editorController,
        onRequireMediaDirectory = onRequireMediaDirectory,
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
    MainScreenConflictHost(
        conflictController = conflictViewModel.dialogController,
        stateBindings = conflictStateViewModel.hostBindings,
    )
    MainScreenFullyDrawnEffect(pagedUiMemos = resolvedPagedUiMemos)
    MainScreenContentHost(
        screenState = screenState,
        pagedUiMemos = resolvedPagedUiMemos,
        hostState = hostState,
        contentBindings = mainBindings.content,
        sidebarBindings = sidebarViewModel.hostBindings,
        editorBindings = editorViewModel.hostBindings,
        recordingBindings = recordingViewModel.hostBindings,
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
        expandCoordinator = expandCoordinator,
    )
}

@Composable
private fun RecoveryDiagnosticExportEffect(
    diagnosticBindings: MainDiagnosticExportBindings,
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

    val diagnosticExports by diagnosticBindings.diagnosticExports.collectAsStateWithLifecycle()
    LaunchedEffect(diagnosticExports, launcher) {
        val event = diagnosticExports.lastOrNull() ?: return@LaunchedEffect
        pendingReport = event.payload
        launcher.launch(event.payload.fileName)
        diagnosticBindings.onConsumeDiagnosticExport(event.id)
    }
}

@Composable
private fun MainScreenPendingNewMemoCreationEffect(
    listState: androidx.compose.foundation.lazy.LazyListState,
    pagedUiMemos: LazyPagingItems<MemoUiModel>,
    creationBindings: MainNewMemoCreationBindings,
    editorBindings: MemoEditorHostBindings,
) {
    val scope = rememberCoroutineScope()
    val latestCreationBindings = rememberUpdatedState(creationBindings)
    val latestEditorBindings = rememberUpdatedState(editorBindings)
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
                        latestCreationBindings.value.enterAnimationRegistry.beginPendingEnter(keys)
                    },
                    bindCommittedEnter = { requestId, memoId ->
                        latestCreationBindings.value.enterAnimationRegistry
                            .bindPendingEnter(requestId, memoId)
                    },
                    startPreparedEnter = { requestId ->
                        latestCreationBindings.value.enterAnimationRegistry.startPendingEnter(requestId)
                    },
                    createdMemoRank = { memoId ->
                        latestCreationBindings.value.rankInActiveMainListQuery(memoId)
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
                            latestCreationBindings.value.onConsumePendingNewMemoCreationEvent(event.id)
                        if (consumedRequest == null) {
                            latestEditorBindings.value.submissions.reject(
                                event.payload.submissionId,
                                IllegalStateException("Pending memo request was already consumed or cancelled"),
                            )
                            null
                        } else {
                            try {
                                latestEditorBindings.value.submissions.create(
                                    submissionId = consumedRequest.submissionId,
                                    content = consumedRequest.content,
                                    timestampMillis = consumedRequest.timestampMillis,
                                    onAccepted = onAccepted,
                                )
                                if (
                                    latestEditorBindings.value.submissions.await(
                                        consumedRequest.submissionId,
                                    )
                                ) {
                                    latestEditorBindings.value.submissions
                                        .committedMemo(consumedRequest.submissionId)?.id
                                } else {
                                    null
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                latestEditorBindings.value.submissions.reject(
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
                        latestCreationBindings.value.enterAnimationRegistry.settleEnter(memoId)
                    },
                    cancelPreparedEnter = { requestId ->
                        latestCreationBindings.value.enterAnimationRegistry.cancelEnterRequest(requestId)
                    },
                ),
            )
        }

    val lifecycleOwner = LocalLifecycleOwner.current
    val requests = creationBindings.pendingNewMemoCreationEvents
    val activeRequests = remember(requests, lifecycleOwner) {
        requests.flowWithLifecycle(lifecycleOwner.lifecycle)
    }
    NewMemoCreationRequestEffect(activeRequests) { event ->
        if (!creationCoordinator.submit(event)) {
            latestCreationBindings.value.onCancelPendingNewMemoCreationEvent(event.id)
            latestEditorBindings.value.submissions.reject(
                event.payload.submissionId,
                IllegalStateException("Memo creation reveal is still in progress"),
            )
        }
    }
}

internal data class DraftAutosaveState(val editingMemoId: String?, val text: String, val isVisible: Boolean)

@Stable
internal data class MainScreenUiSnapshot(
    val searchQuery: String,
    val memoListFilter: MemoListFilter,
    val sidebarUiState: SidebarViewModel.SidebarUiState,
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

@Stable
@OptIn(ExperimentalMaterial3Api::class)
internal data class MainScreenHostState(
    val sidebar: MainSidebarState,
    val scope: CoroutineScope,
    val snackbarHostState: SnackbarHostState,
    val scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    val listState: androidx.compose.foundation.lazy.LazyListState,
    val editorController: MemoEditorController,
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
    effectBindings: MainEffectHostBindings,
    editorBindings: MemoEditorHostBindings,
    recordingBindings: MainRecordingBindings,
    pagedUiMemos: LazyPagingItems<MemoUiModel>,
    searchQuery: String,
    memoListFilter: MemoListFilter,
    canResolveOffscreenMainListFocus: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    editorController: MemoEditorController,
    onRequireMediaDirectory: () -> Unit,
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
        foregroundFlows = effectBindings.foregroundFlows,
        externalCommands = effectBindings.externalCommands,
        recordingBindings = recordingBindings,
        editorBindings = editorBindings,
        foregroundEntryId = foregroundEntryId,
        autoOpenInputOnForeground = autoOpenInputOnForeground,
        canOpenCreateMemo = canOpenCreateMemo,
        pendingNewMemoCreationEvent = pendingNewMemoCreationEvent,
        onRequireVoiceDirectory = onRequireMediaDirectory,
        editorController = editorController,
    )

    MainScreenEventEffectsSurface(
        eventFlows = effectBindings.eventFlows,
        eventActions = effectBindings.eventActions,
        editorBindings = editorBindings,
        onClearRecordingError = recordingBindings.actions.onClearError,
        recordingErrorFlow = recordingBindings.flows.errorMessage,
        pagedUiMemos = pagedUiMemos,
        searchQuery = searchQuery,
        memoListFilter = memoListFilter,
        canResolveOffscreenMainListFocus = canResolveOffscreenMainListFocus,
        listState = listState,
        editorController = editorController,
        onRequireImageDirectory = onRequireMediaDirectory,
        snackbarHostState = snackbarHostState,
        unknownErrorMessage = unknownErrorMessage,
        memoNotFoundMessage = memoNotFoundMessage,
    )
}

@Composable
private fun MainScreenEventEffectsSurface(
    eventFlows: MainEventEffectFlows,
    eventActions: MainEventEffectActions,
    editorBindings: MemoEditorHostBindings,
    onClearRecordingError: () -> Unit,
    recordingErrorFlow: kotlinx.coroutines.flow.StateFlow<String?>,
    pagedUiMemos: LazyPagingItems<MemoUiModel>,
    searchQuery: String,
    memoListFilter: MemoListFilter,
    canResolveOffscreenMainListFocus: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    editorController: MemoEditorController,
    onRequireImageDirectory: () -> Unit,
    snackbarHostState: SnackbarHostState,
    unknownErrorMessage: String,
    memoNotFoundMessage: String,
) {
    val errorMessage by eventFlows.errorMessage.collectAsStateWithLifecycle()
    val imageLocationState by eventFlows.imageLocationState.collectAsStateWithLifecycle()
    val imageLocationError = when (imageLocationState) {
        is ImageLocationSnapshot.Unavailable -> stringResource(R.string.image_directory_scan_failed)
        ImageLocationSnapshot.Loading, is ImageLocationSnapshot.Ready -> null
    }
    val editorErrorMessage by editorBindings.flows.errorMessage.collectAsStateWithLifecycle()
    val recordingErrorMessage by recordingErrorFlow.collectAsStateWithLifecycle()
    val sharedContentEvents by eventFlows.sharedContentEvents.collectAsStateWithLifecycle()
    val pendingSharedImageEvents by eventFlows.pendingSharedImageEvents.collectAsStateWithLifecycle()
    val appActionEvents by eventFlows.appActionEvents.collectAsStateWithLifecycle()
    val imageDirectory by eventFlows.imageDirectory.collectAsStateWithLifecycle()
    val openFullMemoEditor =
        rememberFullMemoEditorOpener(editorController, editorBindings.actions.onReportError)
    val pendingFocusMemoIds =
        remember(appActionEvents) {
            appActionEvents.mapNotNullTo(mutableSetOf()) { event ->
                (event.payload as? MainViewModel.AppAction.FocusMemo)?.memoId
            }
        }
    val focusRetryKey =
        if (pendingFocusMemoIds.isEmpty()) {
            null
        } else {
            val snapshot = pagedUiMemos.itemSnapshotList
            resolveMainListFocusRetryKey(
                searchQuery = searchQuery,
                filter = memoListFilter,
                windowStartIndex = snapshot.placeholdersBefore,
                visibleMemos = snapshot.items,
                pendingFocusMemoIds = pendingFocusMemoIds,
            )
        }
    val onFocusMemoInList: suspend (String) -> MainScreenFocusAttempt =
        remember(pagedUiMemos, canResolveOffscreenMainListFocus, eventActions, listState) {
            { memoId ->
                val snapshot = pagedUiMemos.itemSnapshotList
                focusMemoInMainScreenWithFallback(
                    memoId = memoId,
                    visibleUiMemos = snapshot.items,
                    visibleUiMemoStartIndex = snapshot.placeholdersBefore,
                    canResolveOffscreenMainListFocus = canResolveOffscreenMainListFocus,
                    resolveOffscreenIndex = eventActions.onResolveDefaultMainListIndex,
                    positioner =
                    MainScreenFocusPositioner { index ->
                        // Same pending-modification constraint as the new-memo reveal: a
                        // synchronous scrollToItem can force subcomposition before the
                        // recomposer applies pending list mutations and crash the process.
                        listState.requestScrollToItem(index)
                    },
                )
            }
        }
    val onSaveImage: (Uri, (String) -> Unit, () -> Unit) -> Unit =
        remember(editorBindings) {
            { uri, onResult, onError ->
                editorBindings.actions.onSaveImage(
                    uri,
                    editorBindings.flows.getOwnerDraftId(),
                    onResult,
                    onError,
                )
            }
        }

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
        onFocusMemoInList = onFocusMemoInList,
        onMainListFocusConsumed = eventActions.onClearMainListFocusReanchor,
        focusRetryKey = focusRetryKey,
        onResolveMemoById = eventActions.onResolveMemoById,
        onSaveImage = onSaveImage,
        onRequireImageDirectory = onRequireImageDirectory,
        onConsumeSharedContentEvent = eventActions.onConsumeSharedContentEvent,
        onConsumeAppActionEvent = eventActions.onConsumeAppActionEvent,
        onConsumePendingSharedImageEvent = eventActions.onConsumePendingSharedImageEvent,
        onClearMainError = eventActions.onClearError,
        onClearEditorError = editorBindings.actions.onClearError,
        onClearRecordingError = onClearRecordingError,
    )
}

@Composable
internal fun MainScreenInteractionBindings(
    versionMediaFlows: MainVersionMediaFlows,
    memoInteractions: MainMemoInteractionActions,
    editorBindings: MemoEditorHostBindings,
    recordingBindings: MainRecordingBindings,
    editorController: MemoEditorController,
    onRequireMediaDirectory: () -> Unit,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    unknownErrorMessage: String,
    shareCardShowTime: Boolean,
    shareCardShowSignature: Boolean,
    shareCardSignatureText: String,
    customFontPath: String?,
    quickSaveOnBackEnabled: Boolean,
    inputToolbarToolOrder: ImmutableList<String>,
    availableTags: ImmutableList<String>,
    showInputHints: Boolean,
    onNavigateToShare: (String, Long) -> Unit,
    lanShareEnabled: Boolean,
    content: MainScreenInteractionContent,
) {
    val versionHistoryState by versionMediaFlows.versionHistoryState.collectAsStateWithLifecycle()
    val rootDirectory by versionMediaFlows.rootDirectory.collectAsStateWithLifecycle()
    val imageDirectory by versionMediaFlows.imageDirectory.collectAsStateWithLifecycle()
    val imageMap by versionMediaFlows.imageMap.collectAsStateWithLifecycle()
    val voiceDirectory by versionMediaFlows.voiceDirectory.collectAsStateWithLifecycle()
    val stableImageMap = remember(imageMap) { imageMap.toImmutableMap() }
    val inputHints = rememberInputHints(showInputHints = showInputHints)

    val interactionCallbacks =
        rememberMainScreenInteractionCallbacks(
            memoInteractions = memoInteractions,
            editorBindings = editorBindings,
            recordingActions = recordingBindings.actions,
            editorController = editorController,
            onRequireVoiceDirectory = onRequireMediaDirectory,
            voiceDirectory = voiceDirectory,
            scope = scope,
            snackbarHostState = snackbarHostState,
            unknownErrorMessage = unknownErrorMessage,
        )
    val openFullMemoEditor =
        rememberFullMemoEditorOpener(editorController, editorBindings.actions.onReportError)
    val onLanShare: ((MemoMenuLanShareRequest) -> Unit)? =
        remember(lanShareEnabled, onNavigateToShare) {
            if (lanShareEnabled) {
                { request -> onNavigateToShare(request.content, request.timestamp) }
            } else {
                null
            }
        }
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
            onDeleteMemo = memoInteractions.onDeleteMemo,
            onLanShare = onLanShare,
            contextAction = MemoMenuContextAction.TogglePin(memoInteractions.onSetMemoPinned),
            onVersionHistory = interactionCallbacks.onVersionHistory,
        )

    val isRecording by recordingBindings.flows.isRecording.collectAsStateWithLifecycle()
    val editorSurface =
        rememberMainMemoEditorSurface(
            editorBindings = editorBindings,
            recordingBindings = recordingBindings,
            onUpdateInputToolbarToolOrder = memoInteractions.onUpdateInputToolbarToolOrder,
            interactionCallbacks = interactionCallbacks,
            imageDirectory = imageDirectory,
            rootDirectory = rootDirectory,
            imageMap = stableImageMap,
            availableTags = availableTags,
            inputHints = inputHints,
            quickSaveOnBackEnabled = quickSaveOnBackEnabled,
            inputToolbarToolOrder = inputToolbarToolOrder,
            isRecording = isRecording,
            onImageDirectoryMissing = onRequireMediaDirectory,
        )

    MemoInteractionHost(
        menuCommandHandler = memoMenuCommandHandler,
        controller = editorController,
        editorSurface = editorSurface,
        onEditorOpenFailure = editorBindings.actions.onReportError,
    ) { showMenu, openEditor ->
        content(showMenu, openEditor)
    }

    val onRestoreVersion: (Memo, MemoRevision) -> Unit =
        remember(memoInteractions) {
            { memo, version -> memoInteractions.versionHistory.onRestoreVersion(memo, version) }
        }
    VersionHistoryOverlay(
        state = versionHistoryState,
        rootPath = rootDirectory,
        imagePath = imageDirectory,
        imageMap = stableImageMap,
        onDismiss = memoInteractions.versionHistory.onDismissVersionHistory,
        onLoadMore = memoInteractions.versionHistory.onLoadMoreVersionHistory,
        onRestore = onRestoreVersion,
    )
}

@Composable
private fun rememberMainScreenInteractionCallbacks(
    memoInteractions: MainMemoInteractionActions,
    editorBindings: MemoEditorHostBindings,
    recordingActions: MainRecordingActions,
    editorController: MemoEditorController,
    onRequireVoiceDirectory: () -> Unit,
    voiceDirectory: String?,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    unknownErrorMessage: String,
): MainScreenInteractionCallbacks = remember(
    memoInteractions,
    editorBindings,
    recordingActions,
    editorController,
    onRequireVoiceDirectory,
    voiceDirectory,
    scope,
    snackbarHostState,
    unknownErrorMessage,
) {
    MainScreenInteractionCallbacks(
        onCreateMemo = { submissionId, contentText, timestampMillis ->
            memoInteractions.onRequestPendingNewMemoCreation(
                submissionId,
                contentText,
                timestampMillis,
            )
        },
        onCameraCaptureError = { error ->
            scope.launch {
                snackbarHostState.showSnackbar(error.toUserMessage(unknownErrorMessage))
            }
        },
        onStartRecording = {
            if (voiceDirectory == null) {
                onRequireVoiceDirectory()
            } else {
                recordingActions.onStartRecording(
                    editorController.editingSession?.draftId ?: editorBindings.flows.getOwnerDraftId(),
                )
            }
        },
        onStopRecording = {
            val insertRecording = editorController.captureMarkdownInsertion()
            recordingActions.onStopRecording { markdown ->
                markdown?.let { block ->
                    insertRecording(block)
                    editorBindings.actions.onTrackVoiceMarkdown(block)
                }
            }
        },
        onVersionHistory = { state ->
            memoInteractions.versionHistory.onLoadVersionHistory(state.memo)
        },
    )
}
