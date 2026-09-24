package com.lomo.app.feature.main

import androidx.paging.PagingData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.ExternalAppCommand
import com.lomo.app.ExternalAppCommandStatus
import com.lomo.app.ExternalAppCommandStore
import com.lomo.app.ExternalAppCommandTerminalResult
import com.lomo.app.feature.common.AppConfigStateProvider
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoActionOrderScopes
import com.lomo.app.feature.common.MemoCollectionActionStateHolder
import com.lomo.app.feature.common.MemoCollectionCapabilities
import com.lomo.app.feature.common.MemoCollectionUiState
import com.lomo.app.feature.common.PendingUiEvent
import com.lomo.app.feature.common.UiEventQueueCoordinator
import com.lomo.app.feature.common.UiEventEnqueueResult
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.app.feature.common.newMemoOperationId
import com.lomo.app.feature.common.toUserMessage
import com.lomo.app.feature.memo.MemoActionId
import com.lomo.app.feature.memo.MemoEditorSubmissionId
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoQuerySpec
import com.lomo.domain.model.MemoSortOption
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.ReminderMarker
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.RecoveryDiagnosticReport
import com.lomo.domain.model.canRebuildDerivedIndex
import com.lomo.domain.usecase.MainMemoListQueryUseCase
import com.lomo.domain.usecase.MarkReminderDoneUseCase
import com.lomo.domain.usecase.ObserveActiveDayCountUseCase
import com.lomo.domain.usecase.SetMemoPinnedUseCase
import com.lomo.ui.component.common.EnterAnimationRegistry

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import timber.log.Timber
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

private const val IMAGE_DIRECTORY_SYNC_DEBOUNCE_MILLIS = 300L
private const val MANUAL_REFRESH_TIMEOUT_MILLIS = 30_000L

/** Collaborators of the main screen. */
data class MainViewModelDependencies(
    val mainMemoListQueryUseCase: MainMemoListQueryUseCase,
    val observeActiveDayCountUseCase: ObserveActiveDayCountUseCase,
    val setMemoPinnedUseCase: SetMemoPinnedUseCase,
    val appConfigStateProvider: AppConfigStateProvider,
    val appConfigUiCoordinator: AppConfigUiCoordinator,
    val sidebarStateHolder: MainSidebarStateHolder,
    val versionHistoryCoordinator: MainVersionHistoryCoordinator,
    val memoUiMapper: MemoUiMapper,
    val imageMapProvider: ImageMapProvider,
    val mainMemoMutationCoordinator: MainMemoMutationCoordinator,
    val workspaceCoordinator: MainWorkspaceCoordinator,
    val startupCoordinator: MainStartupCoordinator,
    val markReminderDoneUseCase: MarkReminderDoneUseCase,
    val dispatcherProvider: com.lomo.domain.usecase.DispatcherProvider,
    val externalAppCommandStore: ExternalAppCommandStore,
)

class MainViewModel(
    dependencies: MainViewModelDependencies,
) : ViewModel() {
        private val mainMemoListQueryUseCase = dependencies.mainMemoListQueryUseCase
        private val observeActiveDayCountUseCase = dependencies.observeActiveDayCountUseCase
        private val setMemoPinnedUseCase = dependencies.setMemoPinnedUseCase
        private val appConfigStateProvider = dependencies.appConfigStateProvider
        private val appConfigUiCoordinator = dependencies.appConfigUiCoordinator
        private val sidebarStateHolder = dependencies.sidebarStateHolder
        private val versionHistoryCoordinator = dependencies.versionHistoryCoordinator
        private val memoUiMapper = dependencies.memoUiMapper
        private val imageMapProvider = dependencies.imageMapProvider
        private val mainMemoMutationCoordinator = dependencies.mainMemoMutationCoordinator
        private val workspaceCoordinator = dependencies.workspaceCoordinator
        private val startupCoordinator = dependencies.startupCoordinator
        private val markReminderDoneUseCase = dependencies.markReminderDoneUseCase
        private val dispatcherProvider = dependencies.dispatcherProvider
        private val externalAppCommandStore = dependencies.externalAppCommandStore
        private val _errorMessage = MutableStateFlow<String?>(null)
        private val collectionActionStateHolder =
            MemoCollectionActionStateHolder(
                capabilities =
                    MemoCollectionCapabilities.DeletableTodo(
                        deleteMemo = mainMemoMutationCoordinator::deleteMemo,
                        toggleTodo = { memo, actionSpan ->
                            mainMemoMutationCoordinator.toggleCheckboxLineAndUpdate(memo, actionSpan)
                        },
                    ),
                scope = viewModelScope,
                mapToUiModel = { memo ->
                    memoUiMapper.mapToCachedUiModel(
                        memo = memo,
                        rootPath = rootDirectory.value,
                        imagePath = imageDirectory.value,
                        imageMap = imageMap.value,
                        reminders = memo.reminders,
                    )
                }
            )

        val errorMessage: StateFlow<String?> =
            combine(collectionActionStateHolder.errorMessage, _errorMessage) { collectionError, mainError ->
                mainError ?: collectionError
            }.stateIn(viewModelScope, appWhileSubscribed(), null)

        val isSyncing: StateFlow<Boolean> =
            mainMemoListQueryUseCase
                .isSyncing()
                .stateIn(viewModelScope, appWhileSubscribed(), false)

        private val refreshMutex = Mutex()
        private val lastFocusReanchorId = AtomicReference<String?>(null)
        private val manualRefreshInProgress = MutableStateFlow(false)
        val isRefreshing: StateFlow<Boolean> =
            combine(manualRefreshInProgress, isSyncing) { manual, syncing -> manual || syncing }
                .stateIn(viewModelScope, appWhileSubscribed(), false)

        val searchQuery: StateFlow<String> = sidebarStateHolder.searchQuery
        val memoListFilterController = sidebarStateHolder.filterController
        val memoListFilter: StateFlow<MemoListFilter> = sidebarStateHolder.memoListFilter

        sealed interface MainScreenState {
            data object Loading : MainScreenState

            data object NoDirectory : MainScreenState

            data object OpeningEngine : MainScreenState

            data class ReadOnlyRecovery(
                val code: String,
                val diagnostic: String,
                val canRebuildDerivedIndex: Boolean,
                val hasRecoveryTarget: Boolean,
            ) : MainScreenState

            data object Ready : MainScreenState
        }

        // Shared content is modeled as pending events with explicit consume semantics.
        sealed interface SharedContent {
            data class Text(
                val content: String,
            ) : SharedContent
        }

        private val sharedContentQueue = UiEventQueueCoordinator<SharedContent>()
        val sharedContentEvents: StateFlow<List<PendingUiEvent<SharedContent>>> = sharedContentQueue.events

        private val pendingSharedImageQueue = UiEventQueueCoordinator<android.net.Uri>()
        val pendingSharedImageEvents: StateFlow<List<PendingUiEvent<android.net.Uri>>> = pendingSharedImageQueue.events

        sealed interface AppAction {
            data class OpenMemo(
                val memoId: String,
            ) : AppAction

            data class FocusMemo(
                val memoId: String,
            ) : AppAction
        }

        private val appActionQueue = UiEventQueueCoordinator<AppAction>()
        val appActionEvents: StateFlow<List<PendingUiEvent<AppAction>>> = appActionQueue.events
        val externalAppCommands: StateFlow<List<ExternalAppCommand>> = externalAppCommandStore.commands
        private val pendingNewMemoCreationCoordinator = PendingNewMemoCreationCoordinator()
        private val pendingNewMemoCreationQueue =
            UiEventQueueCoordinator<PendingNewMemoCreationRequest>(maxSize = 1)
        internal val pendingNewMemoCreationEvents: StateFlow<List<PendingUiEvent<PendingNewMemoCreationRequest>>> =
            pendingNewMemoCreationQueue.events

        val deletingMemoIds: StateFlow<Set<String>> = collectionActionStateHolder.deletingMemoIds
        val exitAnimationRegistry = collectionActionStateHolder.exitAnimationRegistry

        val enterAnimationRegistry = EnterAnimationRegistry()

        private val _hasResolvedInitialRoot = MutableStateFlow(false)
        private val _rootDirectory = MutableStateFlow<String?>(null)
        private var imageCacheSyncJob: kotlinx.coroutines.Job? = null
        val rootDirectory: StateFlow<String?> = _rootDirectory.asStateFlow()

        val imageDirectory: StateFlow<String?> = appConfigStateProvider.imageDirectory

        val imageMap: StateFlow<Map<String, android.net.Uri>> = imageMapProvider.imageMap

        val voiceDirectory: StateFlow<String?> = appConfigStateProvider.voiceDirectory

        private val memoListStateHolder =
            MainMemoListStateHolder(
                MainMemoListStateHolderDependencies(
                    scope = viewModelScope,
                    mainMemoListQueryUseCase = mainMemoListQueryUseCase,
                    memoUiMapper = memoUiMapper,
                    searchQuery = searchQuery,
                    memoListFilter = memoListFilter,
                    mount = workspaceCoordinator.mount,
                    rootDirectory = rootDirectory,
                    imageDirectory = imageDirectory,
                    imageMap = imageMap,
                    dispatcherProvider = dispatcherProvider,
                ),
            )

    val mount: StateFlow<com.lomo.domain.model.WorkspaceMount> = workspaceCoordinator.mount

    /**
     * The Activity's explicit engine start request. Native acquisition is only ever triggered by a
     * workspace-needing host, never by Application.onCreate or a transient tile/widget wake.
     */
    fun requestEngineStart() {
        viewModelScope.launch(dispatcherProvider.io) {
            workspaceCoordinator.requestEngineStart()
        }
    }
    private val diagnosticExportQueue = UiEventQueueCoordinator<RecoveryDiagnosticReport>()
    val diagnosticExports: StateFlow<List<PendingUiEvent<RecoveryDiagnosticReport>>> =
        diagnosticExportQueue.events
    val consumeDiagnosticExport: (Long) -> Unit = diagnosticExportQueue::consume

        val uiState: StateFlow<MainScreenState> =
            combine(
                _hasResolvedInitialRoot,
                rootDirectory,
                workspaceCoordinator.mount,
            ) { hasResolvedInitialRoot, directory, mount ->
                val readiness = mount.readiness
                val authority = mount.authority
                when {
                    !hasResolvedInitialRoot -> MainScreenState.Loading
                    readiness is EngineReadiness.ReadOnlyRecovery ->
                        MainScreenState.ReadOnlyRecovery(
                            code = readiness.code,
                            diagnostic = readiness.diagnostic,
                            canRebuildDerivedIndex = readiness.canRebuildDerivedIndex(),
                            hasRecoveryTarget = mount.location != null,
                        )
                    directory == null -> MainScreenState.NoDirectory
                    readiness is EngineReadiness.Opening ||
                        readiness is EngineReadiness.ShuttingDown ->
                        MainScreenState.OpeningEngine
                    authority == null -> MainScreenState.OpeningEngine
                    mount.admitsProjectionReads ->
                        MainScreenState.Ready
                    else -> MainScreenState.OpeningEngine
                }
            }.stateIn(
                viewModelScope,
                appWhileSubscribed(),
                MainScreenState.Loading,
            )

        val pagedUiMemos: Flow<PagingData<MemoUiModel>> = memoListStateHolder.pagedUiMemos

        val galleryPagedUiMemos: Flow<PagingData<MemoUiModel>> = memoListStateHolder.galleryPagedUiMemos


        init {
            viewModelScope.launch {
                workspaceCoordinator.mount
                    .filter { it.readiness !is EngineReadiness.Opening }
                    .collect { mount -> updateRootDirectoryUiState(mount.location?.raw) }
            }
            startupCoordinator.observeRootDirectoryChanges().launchIn(viewModelScope)
            startupCoordinator.observeVoiceDirectoryChanges().launchIn(viewModelScope)

            viewModelScope.launch {
                appConfigStateProvider.preferencesCorruptionNotice.collect { notice ->
                    if (notice != null) {
                        _errorMessage.value =
                            "Settings storage was corrupted and rebuilt with defaults; " +
                                "the original file was kept as ${notice.quarantinedFileName}"
                        appConfigStateProvider.acknowledgeCorruptionNotice()
                    }
                }
            }

            loadImageMap()
        }

        val appPreferences: StateFlow<AppPreferencesState> = appConfigStateProvider.appPreferences

        val appLockEnabled: StateFlow<Boolean?> = appConfigStateProvider.appLockEnabled

        val activeDayCount: StateFlow<Int> =
            observeActiveDayCountUseCase()
                .stateIn(viewModelScope, appWhileSubscribed(), 0)

        val gitSyncEnabled: StateFlow<Boolean> =
            versionHistoryCoordinator
                .historyEnabled()
                .stateIn(viewModelScope, appWhileSubscribed(), false)

        val versionHistoryState: StateFlow<MainVersionHistoryState> = versionHistoryCoordinator.state

        val handleSharedText: (String) -> Unit = { text ->
            enqueueUiCommand(sharedContentQueue, SharedContent.Text(text))
        }

        val handleSharedImage: (android.net.Uri) -> Unit = { uri ->
            enqueueUiCommand(pendingSharedImageQueue, uri)
        }

        val consumeSharedContentEvent: (Long) -> Unit = { eventId ->
            sharedContentQueue.consume(eventId)
        }

        val consumePendingSharedImageEvent: (Long) -> Unit = { eventId ->
            pendingSharedImageQueue.consume(eventId)
        }

        val requestOpenMemo: (String) -> Unit = { memoId ->
            if (memoId.isNotBlank()) {
                enqueueUiCommand(appActionQueue, AppAction.OpenMemo(memoId))
            }
        }

        val requestFocusMemo: (String) -> Unit = { memoId ->
            if (memoId.isNotBlank()) {
                enqueueUiCommand(appActionQueue, AppAction.FocusMemo(memoId))
            }
        }

        val requestFocusMemoInDefaultMainList: (String) -> Unit = { memoId ->
            if (memoId.isNotBlank()) {
                sidebarStateHolder.clearAll()
                enqueueUiCommand(appActionQueue, AppAction.FocusMemo(memoId))
            }
        }

        /**
         * Restore a saved main-list session through the single session owner. Query and filter land
         * before the caller applies the saved viewport anchor.
         */
        internal fun restoreMainListSession(
            query: String,
            filter: MemoListFilter,
        ) {
            sidebarStateHolder.restoreSession(query, filter)
        }

        val consumeAppActionEvent: (Long) -> Unit = { eventId ->
            appActionQueue.consume(eventId)
        }

        val updateExternalAppCommandStatus: (String, ExternalAppCommandStatus) -> Unit = { commandId, status ->
            externalAppCommandStore.updateStatus(commandId = commandId, status = status)
        }

        val completeExternalAppCommand: (String, ExternalAppCommandTerminalResult) -> Unit = { commandId, result ->
            externalAppCommandStore.complete(commandId = commandId, result = result)
        }

        val expireExternalAppCommands: (Long) -> List<String> = externalAppCommandStore::expire

        internal fun requestPendingNewMemoCreation(
            submissionId: MemoEditorSubmissionId,
            content: String,
            timestampMillis: Long? = null,
        ): Boolean {
            if (pendingNewMemoCreationQueue.events.value.isNotEmpty()) {
                return false
            }
            val request = pendingNewMemoCreationCoordinator
                .submit(
                    submissionId = submissionId,
                    content = content,
                    timestampMillis = timestampMillis,
                )
                ?: return false
            if (enqueueUiCommand(pendingNewMemoCreationQueue, request)) return true
            pendingNewMemoCreationCoordinator.cancel(request.requestId)
            return false
        }

        internal val consumePendingNewMemoCreationEvent: (Long) -> PendingNewMemoCreationRequest? =
            consumeEvent@{ eventId ->
                val event =
                    pendingNewMemoCreationQueue.events.value.firstOrNull { pending -> pending.id == eventId }
                        ?: return@consumeEvent null
                pendingNewMemoCreationQueue.consume(eventId)
                pendingNewMemoCreationCoordinator.consume(event.payload.requestId) ?: event.payload
            }

        internal val consumePendingNewMemoCreationRequest: (Long) -> PendingNewMemoCreationRequest? = { requestId ->
            val event =
                pendingNewMemoCreationQueue.events.value.firstOrNull { pending ->
                    pending.payload.requestId == requestId
                }
            if (event != null) {
                pendingNewMemoCreationQueue.consume(event.id)
            }
            pendingNewMemoCreationCoordinator.consume(requestId)
        }

        internal val cancelPendingNewMemoCreationEvent: (Long) -> Unit =
            cancelEvent@{ eventId ->
                val event =
                    pendingNewMemoCreationQueue.events.value.firstOrNull { pending -> pending.id == eventId }
                        ?: return@cancelEvent
                pendingNewMemoCreationQueue.consume(eventId)
                pendingNewMemoCreationCoordinator.cancel(event.payload.requestId)
            }

        internal val cancelPendingNewMemoCreationRequest: (Long) -> Unit = { requestId ->
            pendingNewMemoCreationQueue.events.value
                .firstOrNull { pending -> pending.payload.requestId == requestId }
                ?.let { event -> pendingNewMemoCreationQueue.consume(event.id) }
            pendingNewMemoCreationCoordinator.cancel(requestId)
        }

        val createDefaultDirectories: (Boolean, Boolean) -> Unit = { forImage, forVoice ->
            viewModelScope.launch {
                try {
                    workspaceCoordinator.createDefaultDirectories(forImage, forVoice)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    _errorMessage.value = error.toUserMessage("Failed to create directories")
                }
            }
        }

        val onDirectorySelected: (String) -> Unit = { path ->
            viewModelScope.launch {
                withContext(dispatcherProvider.io) {
                    try {
                        workspaceCoordinator.switchRootAndRefresh(path)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        handleRefreshFailure(
                            throwable = error,
                            fallbackMessage = "Failed to switch storage folder",
                        )
                    }
                }
            }
        }

        val onSearch: (String) -> Unit = { query ->
            sidebarStateHolder.updateSearchQuery(query)
        }

        val updateMemoSortOption: (MemoSortOption) -> Unit = memoListFilterController.onSortOptionSelected
        val updateMemoStartDate: (LocalDate?) -> Unit = memoListFilterController.onStartDateSelected
        val updateMemoEndDate: (LocalDate?) -> Unit = memoListFilterController.onEndDateSelected
        val updateMemoHasTodo: (Boolean?) -> Unit = memoListFilterController.onHasTodoChanged
        val updateMemoHasAttachment: (Boolean?) -> Unit = memoListFilterController.onHasAttachmentChanged
        val updateMemoHasUrl: (Boolean?) -> Unit = memoListFilterController.onHasUrlChanged
        val filterMemosByDate: (LocalDate) -> Unit = memoListFilterController.filterByDate
        val clearMemoDateRange: () -> Unit = memoListFilterController.clearDateRange
        val clearMemoFilter: () -> Unit = memoListFilterController.clearFilter

        val refresh: suspend () -> Unit = refresh@{
            if (!refreshMutex.tryLock()) return@refresh
            manualRefreshInProgress.value = true
            try {
                withContext(dispatcherProvider.io) {
                    try {
                        withTimeout(MANUAL_REFRESH_TIMEOUT_MILLIS) {
                            workspaceCoordinator.refreshMemos()
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        handleRefreshFailure(throwable = error, fallbackMessage = "Failed to refresh memos")
                    }
                }
            } finally {
                refreshMutex.unlock()
                manualRefreshInProgress.value = false
            }
        }

        val resolveMemoById: suspend (String) -> Memo? = { memoId ->
            withContext(dispatcherProvider.io) {
                mainMemoListQueryUseCase.getMemoById(memoId)
            }
        }

        val resolveDefaultMainListIndex: suspend (String) -> Int? = { memoId ->
            withContext(dispatcherProvider.io) {
                val rank = mainMemoListQueryUseCase.rankInDefaultMainList(memoId)
                if (rank != null && lastFocusReanchorId.getAndSet(memoId) != memoId) {
                    mainMemoListQueryUseCase.reanchorMainListToIdentity(memoId)
                }
                rank
            }
        }

        fun clearMainListFocusReanchor() {
            lastFocusReanchorId.set(null)
        }

        /**
         * Engine-evaluated rank of a committed memo under the session's live query/filter.
         * The new-memo reveal pipeline uses this to decide whether the created memo can ever be
         * the visible head — a memo the active spec does not admit, or one that ranks behind
         * pinned/older rows, must not trigger the bounded new-head wait.
         */
        internal suspend fun rankInActiveMainListQuery(memoId: String): Int? =
            withContext(dispatcherProvider.io) {
                mainMemoListQueryUseCase.rankInMainListQuery(
                    spec =
                        MemoQuerySpec.fromFilter(
                            queryText = sidebarStateHolder.searchQuery.value,
                            filter = sidebarStateHolder.memoListFilter.value,
                        ),
                    id = memoId,
                )
            }

        val deleteMemo: (Memo, String?) -> Unit = { memo, anchoredAfterKey ->
            collectionActionStateHolder.actions.delete(memo, anchoredAfterKey)
        }

        internal fun onPagedDeleteAnimationSettled(memoId: String) {
            exitAnimationRegistry.markExitAnimationSettled(memoId)
            exitAnimationRegistry.markExitSourceAbsent(memoId)
        }


        val markReminderDone: (String, String) -> Unit = { memoId, reminderId ->
            viewModelScope.launch(dispatcherProvider.io) {
                try {
                    markReminderDoneUseCase(memoId, reminderId)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    _errorMessage.value = error.toUserMessage("Failed to mark reminder done")
                }
            }
        }

        val setMemoPinned: (Memo, Boolean) -> Unit = { memo, pinned ->
            viewModelScope.launch(dispatcherProvider.io) {
                try {
                    setMemoPinnedUseCase(memo.id, pinned, newMemoOperationId())
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    _errorMessage.value = error.toUserMessage("Failed to update pin status")
                }
            }
        }

        val updateMemo: (Memo, com.lomo.domain.model.markdown.MarkdownSourceSpan) -> Unit = { memo, actionSpan ->
            collectionActionStateHolder.actions.toggleTodo(memo, actionSpan)
        }

        private fun requestImageCacheSync(fallbackMessage: String) {
            if (imageCacheSyncJob?.isActive == true) {
                return
            }
            imageCacheSyncJob =
                viewModelScope.launch {
                    try {
                        try {
                            workspaceCoordinator.syncImageCache()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            _errorMessage.value = error.toUserMessage(fallbackMessage)
                        }
                    } finally {
                        imageCacheSyncJob = null
                    }
                }
        }

        val loadVersionHistory: (Memo) -> Unit = { memo ->
            viewModelScope.launch(dispatcherProvider.io) {
                try {
                    versionHistoryCoordinator.load(memo)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Timber.w(error, "Failed to load version history")
                    versionHistoryCoordinator.hide()
                    _errorMessage.value = error.toUserMessage("Failed to load version history")
                }
            }
        }

        val loadMoreVersionHistory: () -> Unit = {
            viewModelScope.launch(dispatcherProvider.io) {
                try {
                    versionHistoryCoordinator.loadMore()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    _errorMessage.value = error.toUserMessage("Failed to load more version history")
                }
            }
        }

        val restoreVersion: (Memo, MemoRevision) -> Unit = { memo, version ->
            viewModelScope.launch(dispatcherProvider.io) {
                try {
                    versionHistoryCoordinator.restore(memo, version)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    _errorMessage.value = error.toUserMessage("Failed to restore version")
                }
            }
        }

        val dismissVersionHistory: () -> Unit = {
            versionHistoryCoordinator.hide()
        }

        val recordMemoActionUsage: (MemoActionId) -> Unit = { actionId ->
            viewModelScope.launch {
                appConfigUiCoordinator.recordMemoActionUsage(actionId.storageKey)
            }
        }

        val recordGalleryMemoActionUsage: (MemoActionId) -> Unit = { actionId ->
            viewModelScope.launch {
                appConfigUiCoordinator.recordMemoActionUsage(
                    scope = MemoActionOrderScopes.GALLERY,
                    actionId = actionId.storageKey,
                )
            }
        }

        val updateMemoActionOrder: (List<MemoActionId>) -> Unit = { actionIds ->
            viewModelScope.launch {
                appConfigUiCoordinator.updateMemoActionOrder(
                    actionIds.map(MemoActionId::storageKey),
                )
            }
        }

        val updateGalleryMemoActionOrder: (List<MemoActionId>) -> Unit = { actionIds ->
            viewModelScope.launch {
                appConfigUiCoordinator.updateMemoActionOrder(
                    scope = MemoActionOrderScopes.GALLERY,
                    order = actionIds.map(MemoActionId::storageKey),
                )
            }
        }

        val updateInputToolbarToolOrder: (List<String>) -> Unit = { toolIds ->
            viewModelScope.launch {
                appConfigUiCoordinator.updateInputToolbarToolOrder(toolIds)
            }
        }

        val clearError: () -> Unit = {
            collectionActionStateHolder.errors.clear()
            _errorMessage.value = null
        }

        private fun updateRootDirectoryUiState(directory: String?) {
            _rootDirectory.value = directory
            _hasResolvedInitialRoot.value = true
        }

        @OptIn(FlowPreview::class)
        private fun loadImageMap() {
            viewModelScope.launch {
                val initialConfiguredImageDirectory = appConfigStateProvider.currentImageDirectory()
                imageDirectory
                    .filterNotNull()
                    .distinctUntilChanged()
                    .filter { directory -> directory != initialConfiguredImageDirectory }
                    .debounce(IMAGE_DIRECTORY_SYNC_DEBOUNCE_MILLIS)
                    .collect { requestImageCacheSync("Failed to sync image cache") }
            }
        }

        val retryEngineOpen: () -> Unit = {
            viewModelScope.launch {
                try {
                    val location = workspaceCoordinator.mount.value.location?.raw
                    if (location == null) {
                        _errorMessage.value = "No workspace location is available to retry opening the engine"
                    } else {
                        workspaceCoordinator.retryEngineOpen(location)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Timber.w(error, "Engine reopen failed")
                    _errorMessage.value = error.toUserMessage("Failed to reopen workspace")
                }
            }
        }

        val rebuildDerivedIndex: () -> Unit = {
            viewModelScope.launch(dispatcherProvider.io) {
                try {
                    workspaceCoordinator.rebuildDerivedIndex()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Timber.w(error, "Derived index rebuild failed")
                    _errorMessage.value = error.toUserMessage("Failed to rebuild the derived index")
                }
            }
        }

        val exportRecoveryDiagnostics: () -> Unit = {
            viewModelScope.launch(dispatcherProvider.io) {
                try {
                    val report = workspaceCoordinator.createRecoveryDiagnosticReport()
                    enqueueUiCommand(diagnosticExportQueue, report)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Timber.w(error, "Recovery diagnostic export failed")
                    _errorMessage.value = error.toUserMessage("Failed to prepare recovery diagnostics")
                }
            }
        }

        private fun <T> enqueueUiCommand(queue: UiEventQueueCoordinator<T>, payload: T): Boolean =
            when (queue.enqueue(payload)) {
                is UiEventEnqueueResult.Accepted -> true
                is UiEventEnqueueResult.Rejected -> {
                    _errorMessage.value = "Too many pending actions. Complete an earlier action and try again."
                    false
                }
            }

        private fun handleRefreshFailure(
            throwable: Throwable,
            fallbackMessage: String,
        ) {
            when (throwable) {
                is kotlinx.coroutines.CancellationException -> throw throwable
                is com.lomo.domain.usecase.SyncConflictException ->
                    timber.log.Timber.w(
                        "Remote sync conflict requires Sync Center: %d file(s)",
                        throwable.conflicts.files.size,
                    )
                else -> _errorMessage.value = throwable.toUserMessage(fallbackMessage)
            }
        }

        // processMemoContent moved to MemoUiMapper
    }

data class MemoUiModel(
    val memo: Memo,
    val processedContent: String,
    val renderDocument: com.lomo.domain.model.markdown.MarkdownRenderDocument,
    val presentationPlan: com.lomo.ui.component.markdown.MarkdownIrPresentationPlan =
        com.lomo.ui.component.markdown.buildMarkdownIrPresentationPlan(
            document = renderDocument,
            policy = com.lomo.ui.component.markdown.MarkdownPresentationPolicy.MEMO_CARD,
        ),
    val tags: ImmutableList<String>,
    val imageUrls: ImmutableList<String> = persistentListOf(),
    val shouldShowExpand: Boolean = false,
    val collapsedSummary: String = "",
    val reminders: ImmutableList<ReminderMarker> = persistentListOf(),
)
