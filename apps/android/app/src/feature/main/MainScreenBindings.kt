package com.lomo.app.feature.main

import android.net.Uri
import androidx.compose.runtime.Stable
import com.lomo.app.ExternalAppCommand
import com.lomo.app.ExternalAppCommandStatus
import com.lomo.app.ExternalAppCommandTerminalResult
import com.lomo.app.feature.common.MemoListFilterController
import com.lomo.app.feature.common.PendingUiEvent
import com.lomo.app.feature.memo.MemoEditorSubmissionId
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.domain.model.DraftId
import com.lomo.domain.model.ImageLocationSnapshot
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.RecoveryDiagnosticReport
import com.lomo.domain.model.WorkspaceMount
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.ui.component.common.EnterAnimationRegistry
import com.lomo.ui.component.common.ExitAnimationRegistry
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/** Reactive recording state streams exposed by [RecordingViewModel] to MainScreen hosts. */
@Stable
internal data class MainRecordingFlows(
    val isRecording: StateFlow<Boolean>,
    val recordingDuration: StateFlow<Long>,
    val recordingAmplitude: StateFlow<Int?>,
    val recordingCaptureId: StateFlow<String?>,
    val errorMessage: StateFlow<String?>,
)

/** Recording control actions exposed by [RecordingViewModel] to MainScreen hosts. */
@Stable
internal data class MainRecordingActions(
    val onStartRecording: (DraftId) -> Unit,
    val onStopRecording: ((String?) -> Unit) -> Unit,
    val onCancelRecording: () -> Unit,
    val onClearError: () -> Unit,
)

/** Host binding bundle for [RecordingViewModel]. */
@Stable
internal data class MainRecordingBindings(val flows: MainRecordingFlows, val actions: MainRecordingActions)

/** Host binding bundle for [SidebarViewModel]. */
@Stable
internal data class MainSidebarBindings(
    val searchQuery: StateFlow<String>,
    val sidebarUiState: StateFlow<SidebarViewModel.SidebarUiState>,
    val onUpdateTagOrder: (List<String>) -> Unit,
)

/** Diagnostic document export bindings from [MainViewModel]. */
@Stable
internal data class MainDiagnosticExportBindings(
    val diagnosticExports: StateFlow<List<PendingUiEvent<RecoveryDiagnosticReport>>>,
    val onConsumeDiagnosticExport: (Long) -> Unit,
)

/** Main-screen UI snapshot flows read at the screen root. */
@Stable
internal data class MainUiSnapshotFlows(
    val memoListFilter: StateFlow<MemoListFilter>,
    val appPreferences: StateFlow<AppPreferencesState>,
    val uiState: StateFlow<MainViewModel.MainScreenState>,
    val pendingNewMemoCreationEvents: StateFlow<List<PendingUiEvent<PendingNewMemoCreationRequest>>>,
)

/** Bindings driving the coordinated new-memo creation and enter reveal pipeline. */
@Stable
internal data class MainNewMemoCreationBindings(
    val enterAnimationRegistry: EnterAnimationRegistry,
    val pendingNewMemoCreationEvents: StateFlow<List<PendingUiEvent<PendingNewMemoCreationRequest>>>,
    val rankInActiveMainListQuery: suspend (String) -> Int?,
    val onConsumePendingNewMemoCreationEvent: (Long) -> PendingNewMemoCreationRequest?,
    val onCancelPendingNewMemoCreationEvent: (Long) -> Unit,
)

/** Foreground entry and external command streams read by foreground effect hosts. */
@Stable
internal data class MainForegroundEffectFlows(
    val uiState: StateFlow<MainViewModel.MainScreenState>,
    val sharedContentEvents: StateFlow<List<PendingUiEvent<MainViewModel.SharedContent>>>,
    val pendingSharedImageEvents: StateFlow<List<PendingUiEvent<Uri>>>,
    val appActionEvents: StateFlow<List<PendingUiEvent<MainViewModel.AppAction>>>,
    val externalAppCommands: StateFlow<List<ExternalAppCommand>>,
    val voiceDirectory: StateFlow<String?>,
)

/** Lifecycle callbacks updating external app command status in [MainViewModel]. */
@Stable
internal data class MainExternalCommandActions(
    val onExpireExternalAppCommands: (Long) -> List<String>,
    val onUpdateExternalAppCommandStatus: (String, ExternalAppCommandStatus) -> Unit,
    val onCompleteExternalAppCommand: (String, ExternalAppCommandTerminalResult) -> Unit,
)

/** Error, share, image, and action streams consumed by event effect hosts. */
@Stable
internal data class MainEventEffectFlows(
    val errorMessage: StateFlow<String?>,
    val imageLocationState: StateFlow<ImageLocationSnapshot>,
    val sharedContentEvents: StateFlow<List<PendingUiEvent<MainViewModel.SharedContent>>>,
    val pendingSharedImageEvents: StateFlow<List<PendingUiEvent<Uri>>>,
    val appActionEvents: StateFlow<List<PendingUiEvent<MainViewModel.AppAction>>>,
    val imageDirectory: StateFlow<String?>,
)

/** One-shot focus resolution and event consumption actions for event effect hosts. */
@Stable
internal data class MainEventEffectActions(
    val onResolveDefaultMainListIndex: suspend (String) -> Int?,
    val onClearMainListFocusReanchor: () -> Unit,
    val onResolveMemoById: suspend (String) -> Memo?,
    val onConsumeSharedContentEvent: (Long) -> Unit,
    val onConsumeAppActionEvent: (Long) -> Unit,
    val onConsumePendingSharedImageEvent: (Long) -> Unit,
    val onClearError: () -> Unit,
)

/** Combined non-ViewModel bindings for foreground, external command, and event effects. */
@Stable
internal data class MainEffectHostBindings(
    val foregroundFlows: MainForegroundEffectFlows,
    val externalCommands: MainExternalCommandActions,
    val eventFlows: MainEventEffectFlows,
    val eventActions: MainEventEffectActions,
)

/** Media and version history flows consumed by [MainScreenInteractionBindings]. */
@Stable
internal data class MainVersionMediaFlows(
    val versionHistoryState: StateFlow<MainVersionHistoryState>,
    val rootDirectory: StateFlow<String?>,
    val imageDirectory: StateFlow<String?>,
    val imageMap: StateFlow<Map<String, Uri>>,
    val voiceDirectory: StateFlow<String?>,
)

/** Version history actions from [MainViewModel]. */
@Stable
internal data class MainVersionHistoryActions(
    val onLoadVersionHistory: (Memo) -> Unit,
    val onDismissVersionHistory: () -> Unit,
    val onLoadMoreVersionHistory: () -> Unit,
    val onRestoreVersion: (Memo, MemoRevision) -> Unit,
)

/** Memo mutation, toolbar order, creation, and version-history callbacks for interaction hosts. */
@Stable
internal data class MainMemoInteractionActions(
    val onDeleteMemo: (Memo, String?) -> Unit,
    val onSetMemoPinned: (Memo, Boolean) -> Unit,
    val onUpdateInputToolbarToolOrder: (List<String>) -> Unit,
    val onRequestPendingNewMemoCreation: (MemoEditorSubmissionId, String, Long?) -> Boolean,
    val versionHistory: MainVersionHistoryActions,
)

/** Workspace mount and filter state bindings driving main-list session restoration. */
@Stable
internal data class MainSessionRestoreBindings(
    val mount: StateFlow<WorkspaceMount>,
    val searchQuery: StateFlow<String>,
    val memoListFilter: StateFlow<MemoListFilter>,
    val onRestoreMainListSession: (String, MemoListFilter) -> Unit,
)

/** Navigation, quick-ball, and filter callbacks for main navigation hosts. */
@Stable
internal data class MainNavigationActions(
    val onRequestQuickBallRecording: (String) -> Unit,
    val onRefreshMemos: suspend () -> Unit,
    val onMarkReminderDone: (String, String) -> Unit,
    val onClearMemoFilter: () -> Unit,
    val onFilterMemosByDate: (LocalDate) -> Unit,
)

/** Feed animation, filter controller, and recovery callbacks consumed by [MainScreenNavigationRender]. */
@Stable
internal data class MainListRenderBindings(
    val exitAnimationRegistry: ExitAnimationRegistry<MemoUiModel>,
    val enterAnimationRegistry: EnterAnimationRegistry,
    val onUpdateMemo: (Memo, MarkdownSourceSpan) -> Unit,
    val memoListFilterController: MemoListFilterController,
    val onRetryEngineOpen: () -> Unit,
    val onRebuildDerivedIndex: () -> Unit,
    val onExportRecoveryDiagnostics: () -> Unit,
)

/** Non-ViewModel content binding bundle exposed by [MainViewModel] to [MainScreenContentHost]. */
@Stable
internal data class MainContentHostBindings(
    val versionMediaFlows: MainVersionMediaFlows,
    val memoInteractions: MainMemoInteractionActions,
    val sessionRestore: MainSessionRestoreBindings,
    val navigationActions: MainNavigationActions,
    val listRender: MainListRenderBindings,
)

/** Non-ViewModel host bindings required to execute planned external app commands. */
@Stable
internal data class MainExternalCommandExecutionTarget(
    val commandActions: MainExternalCommandActions,
    val recordingActions: MainRecordingActions,
    val editorBindings: com.lomo.app.feature.memo.MemoEditorHostBindings,
)

/** Bindings driving [MainVersionHistoryEffectHost] from secondary screens without passing [MainViewModel]. */
@Stable
internal data class MainVersionHistoryEffectBindings(
    val versionHistoryStateFlow: StateFlow<MainVersionHistoryState>,
    val rootDirectoryFlow: StateFlow<String?>,
    val imageDirectoryFlow: StateFlow<String?>,
    val imageMapFlow: StateFlow<Map<String, Uri>>,
    val onDismiss: () -> Unit,
    val onLoadMore: () -> Unit,
    val onRestore: (Memo, MemoRevision) -> Unit,
)

/** Root non-ViewModel capability bundle exposed by [MainViewModel] to [MainScreen]. */
@Stable
internal data class MainHostBindings(
    val diagnosticExport: MainDiagnosticExportBindings,
    val snapshotFlows: MainUiSnapshotFlows,
    val creation: MainNewMemoCreationBindings,
    val effects: MainEffectHostBindings,
    val content: MainContentHostBindings,
)

internal fun MainViewModel.buildVersionHistoryEffectBindings(): MainVersionHistoryEffectBindings =
    MainVersionHistoryEffectBindings(
        versionHistoryStateFlow = versionHistoryState,
        rootDirectoryFlow = rootDirectory,
        imageDirectoryFlow = imageDirectory,
        imageMapFlow = imageMap,
        onDismiss = dismissVersionHistory,
        onLoadMore = loadMoreVersionHistory,
        onRestore = restoreVersion,
    )

internal fun MainViewModel.buildMainHostBindings(): MainHostBindings = MainHostBindings(
    diagnosticExport =
    MainDiagnosticExportBindings(
        diagnosticExports = diagnosticExports,
        onConsumeDiagnosticExport = consumeDiagnosticExport,
    ),
    snapshotFlows =
    MainUiSnapshotFlows(
        memoListFilter = memoListFilter,
        appPreferences = appPreferences,
        uiState = uiState,
        pendingNewMemoCreationEvents = pendingNewMemoCreationEvents,
    ),
    creation =
    MainNewMemoCreationBindings(
        enterAnimationRegistry = enterAnimationRegistry,
        pendingNewMemoCreationEvents = pendingNewMemoCreationEvents,
        rankInActiveMainListQuery = ::rankInActiveMainListQuery,
        onConsumePendingNewMemoCreationEvent = consumePendingNewMemoCreationEvent,
        onCancelPendingNewMemoCreationEvent = cancelPendingNewMemoCreationEvent,
    ),
    effects = buildEffectHostBindings(),
    content = buildContentHostBindings(),
)

private fun MainViewModel.buildEffectHostBindings(): MainEffectHostBindings = MainEffectHostBindings(
    foregroundFlows =
    MainForegroundEffectFlows(
        uiState = uiState,
        sharedContentEvents = sharedContentEvents,
        pendingSharedImageEvents = pendingSharedImageEvents,
        appActionEvents = appActionEvents,
        externalAppCommands = externalAppCommands,
        voiceDirectory = voiceDirectory,
    ),
    externalCommands =
    MainExternalCommandActions(
        onExpireExternalAppCommands = expireExternalAppCommands,
        onUpdateExternalAppCommandStatus = updateExternalAppCommandStatus,
        onCompleteExternalAppCommand = completeExternalAppCommand,
    ),
    eventFlows =
    MainEventEffectFlows(
        errorMessage = errorMessage,
        imageLocationState = imageLocationState,
        sharedContentEvents = sharedContentEvents,
        pendingSharedImageEvents = pendingSharedImageEvents,
        appActionEvents = appActionEvents,
        imageDirectory = imageDirectory,
    ),
    eventActions =
    MainEventEffectActions(
        onResolveDefaultMainListIndex = resolveDefaultMainListIndex,
        onClearMainListFocusReanchor = ::clearMainListFocusReanchor,
        onResolveMemoById = resolveMemoById,
        onConsumeSharedContentEvent = consumeSharedContentEvent,
        onConsumeAppActionEvent = consumeAppActionEvent,
        onConsumePendingSharedImageEvent = consumePendingSharedImageEvent,
        onClearError = clearError,
    ),
)

private fun MainViewModel.buildContentHostBindings(): MainContentHostBindings = MainContentHostBindings(
    versionMediaFlows =
    MainVersionMediaFlows(
        versionHistoryState = versionHistoryState,
        rootDirectory = rootDirectory,
        imageDirectory = imageDirectory,
        imageMap = imageMap,
        voiceDirectory = voiceDirectory,
    ),
    memoInteractions =
    MainMemoInteractionActions(
        onDeleteMemo = deleteMemo,
        onSetMemoPinned = setMemoPinned,
        onUpdateInputToolbarToolOrder = updateInputToolbarToolOrder,
        onRequestPendingNewMemoCreation = ::requestPendingNewMemoCreation,
        versionHistory =
        MainVersionHistoryActions(
            onLoadVersionHistory = loadVersionHistory,
            onDismissVersionHistory = dismissVersionHistory,
            onLoadMoreVersionHistory = loadMoreVersionHistory,
            onRestoreVersion = restoreVersion,
        ),
    ),
    sessionRestore =
    MainSessionRestoreBindings(
        mount = mount,
        searchQuery = searchQuery,
        memoListFilter = memoListFilter,
        onRestoreMainListSession = ::restoreMainListSession,
    ),
    navigationActions =
    MainNavigationActions(
        onRequestQuickBallRecording = requestQuickBallRecording,
        onRefreshMemos = refresh,
        onMarkReminderDone = markReminderDone,
        onClearMemoFilter = clearMemoFilter,
        onFilterMemosByDate = filterMemosByDate,
    ),
    listRender =
    MainListRenderBindings(
        exitAnimationRegistry = exitAnimationRegistry,
        enterAnimationRegistry = enterAnimationRegistry,
        onUpdateMemo = updateMemo,
        memoListFilterController = memoListFilterController,
        onRetryEngineOpen = retryEngineOpen,
        onRebuildDerivedIndex = rebuildDerivedIndex,
        onExportRecoveryDiagnostics = exportRecoveryDiagnostics,
    ),
)
