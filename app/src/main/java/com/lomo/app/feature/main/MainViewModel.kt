package com.lomo.app.feature.main

import androidx.paging.PagingData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.AppConfigStateProvider
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoActionOrderScopes
import com.lomo.app.feature.common.MemoUiCoordinator
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.app.feature.common.runDeleteAnimationWithRollback
import com.lomo.app.feature.common.toUserMessage
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoSortOption
import com.lomo.domain.model.MemoRevision
import com.lomo.ui.component.menu.MemoActionId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val AUTO_REFRESH_MIN_INTERVAL_MILLIS = 45_000L
private const val IMAGE_DIRECTORY_SYNC_DEBOUNCE_MILLIS = 300L

@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        private val memoUiCoordinator: MemoUiCoordinator,
        private val appConfigStateProvider: AppConfigStateProvider,
        private val appConfigUiCoordinator: AppConfigUiCoordinator,
        private val sidebarStateHolder: MainSidebarStateHolder,
        private val versionHistoryCoordinator: MainVersionHistoryCoordinator,
        private val memoUiMapper: MemoUiMapper,
        private val imageMapProvider: ImageMapProvider,
        private val mainMemoMutationCoordinator: MainMemoMutationCoordinator,
        private val workspaceCoordinator: MainWorkspaceCoordinator,
        private val startupCoordinator: MainStartupCoordinator,
    ) : ViewModel() {
        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage

        private val _syncConflictEvent =
            kotlinx.coroutines.flow.MutableSharedFlow<com.lomo.domain.model.SyncConflictSet>(
                extraBufferCapacity = 1,
            )
        val syncConflictEvent:
            kotlinx.coroutines.flow.SharedFlow<com.lomo.domain.model.SyncConflictSet> = _syncConflictEvent

        val isSyncing: StateFlow<Boolean> =
            memoUiCoordinator
                .isSyncing()
                .stateIn(viewModelScope, appWhileSubscribed(), false)

        val searchQuery: StateFlow<String> = sidebarStateHolder.searchQuery
        private val _memoListFilter = MutableStateFlow(MemoListFilter())
        val memoListFilter: StateFlow<MemoListFilter> = _memoListFilter

        sealed interface MainScreenState {
            data object Loading : MainScreenState

            data object NoDirectory : MainScreenState

            data object InitialImporting : MainScreenState

            data object Ready : MainScreenState
        }

        // Shared content is modeled as pending events with explicit consume semantics.
        sealed interface SharedContent {
            data class Text(
                val content: String,
            ) : SharedContent
        }

        private val sharedContentQueue = MainEventQueueCoordinator<SharedContent>()
        val sharedContentEvents: StateFlow<List<PendingUiEvent<SharedContent>>> = sharedContentQueue.events

        private val pendingSharedImageQueue = MainEventQueueCoordinator<android.net.Uri>()
        val pendingSharedImageEvents: StateFlow<List<PendingUiEvent<android.net.Uri>>> = pendingSharedImageQueue.events

        sealed interface AppAction {
            data object CreateMemo : AppAction

            data class OpenMemo(
                val memoId: String,
            ) : AppAction

            data class FocusMemo(
                val memoId: String,
            ) : AppAction
        }

        private val appActionQueue = MainEventQueueCoordinator<AppAction>()
        val appActionEvents: StateFlow<List<PendingUiEvent<AppAction>>> = appActionQueue.events
        private val pendingNewMemoCreationCoordinator = PendingNewMemoCreationCoordinator()
        private val _pendingNewMemoCreationRequest = MutableStateFlow<PendingNewMemoCreationRequest?>(null)
        internal val pendingNewMemoCreationRequest: StateFlow<PendingNewMemoCreationRequest?> =
            _pendingNewMemoCreationRequest.asStateFlow()

        private val _deletingMemoIds = MutableStateFlow<Set<String>>(emptySet())
        val deletingMemoIds: StateFlow<Set<String>> = _deletingMemoIds.asStateFlow()

        private val _hasResolvedInitialRoot = MutableStateFlow(false)
        private val _isInitialDirectoryImporting = MutableStateFlow(false)
        private val _rootDirectory = MutableStateFlow<String?>(null)
        private val imageCacheSyncReady = MutableStateFlow(false)
        private var automaticRefreshJob: kotlinx.coroutines.Job? = null
        private var imageCacheSyncJob: kotlinx.coroutines.Job? = null
        private var lastAutomaticRefreshMark: TimeMark? = null
        private val manualRootRefreshPath = AtomicReference<String?>(null)
        private val deferredStartupRequested = AtomicBoolean(false)
        val rootDirectory: StateFlow<String?> = _rootDirectory.asStateFlow()

        val imageDirectory: StateFlow<String?> = appConfigStateProvider.imageDirectory

        val imageMap: StateFlow<Map<String, android.net.Uri>> = imageMapProvider.imageMap

        val voiceDirectory: StateFlow<String?> = appConfigStateProvider.voiceDirectory

        private val memoListStateHolder =
            MainMemoListStateHolder(
                scope = viewModelScope,
                memoUiCoordinator = memoUiCoordinator,
                memoUiMapper = memoUiMapper,
                searchQuery = searchQuery,
                memoListFilter = memoListFilter,
                rootDirectory = rootDirectory,
                imageDirectory = imageDirectory,
                imageMap = imageMap,
            )

        val uiState: StateFlow<MainScreenState> =
            combine(_hasResolvedInitialRoot, rootDirectory, _isInitialDirectoryImporting) {
                hasResolvedInitialRoot,
                directory,
                isInitialDirectoryImporting,
                ->
                when {
                    !hasResolvedInitialRoot -> MainScreenState.Loading
                    directory == null -> MainScreenState.NoDirectory
                    isInitialDirectoryImporting -> MainScreenState.InitialImporting
                    else -> MainScreenState.Ready
                }
            }.stateIn(
                viewModelScope,
                kotlinx.coroutines.flow.SharingStarted.Eagerly,
                MainScreenState.Loading,
            )

        val pagedUiMemos: Flow<PagingData<MemoUiModel>> = memoListStateHolder.pagedUiMemos

        val mainListTotalCount: StateFlow<Int> = memoListStateHolder.mainListTotalCount

        val galleryUiMemosState: StateFlow<GalleryUiMemosState> = memoListStateHolder.galleryUiMemosState

        val galleryUiMemos: StateFlow<List<MemoUiModel>> = memoListStateHolder.galleryUiMemos

        init {
            // P1-002 Fix: Consolidated initialization to prevent race condition
            // First get initial value synchronously, then listen for subsequent updates
            viewModelScope.launch {
                // Step 1: Get initial value and set state immediately
                val initialDir = startupCoordinator.initializeRootDirectory()
                updateRootDirectoryUiState(initialDir)

                // Step 2: Listen for persisted root updates; unchanged restored values are ignored below.
                startupCoordinator
                    .observeRootDirectoryChanges()
                    .collect { dir ->
                        handleObservedRootDirectoryChange(dir)
                    }
            }
            // Voice directory collector - pass to AudioPlayerManager for voice file resolution
            startupCoordinator.observeVoiceDirectoryChanges().launchIn(viewModelScope)

            // Initial image load
            loadImageMap()
        }

        val appPreferences: StateFlow<AppPreferencesState> = appConfigStateProvider.appPreferences

        val appLockEnabled: StateFlow<Boolean?> = appConfigStateProvider.appLockEnabled

        val activeDayCount: StateFlow<Int> =
            memoUiCoordinator
                .activeDayCount()
                .stateIn(viewModelScope, appWhileSubscribed(), 0)

        val gitSyncEnabled: StateFlow<Boolean> =
            versionHistoryCoordinator
                .historyEnabled()
                .stateIn(viewModelScope, appWhileSubscribed(), false)

        val versionHistoryState: StateFlow<MainVersionHistoryState> = versionHistoryCoordinator.state

        val handleSharedText: (String) -> Unit = { text ->
            sharedContentQueue.enqueue(SharedContent.Text(text))
        }

        val handleSharedImage: (android.net.Uri) -> Unit = { uri ->
            pendingSharedImageQueue.enqueue(uri)
        }

        val consumeSharedContentEvent: (Long) -> Unit = { eventId ->
            sharedContentQueue.consume(eventId)
        }

        val consumePendingSharedImageEvent: (Long) -> Unit = { eventId ->
            pendingSharedImageQueue.consume(eventId)
        }

        val requestCreateMemo: () -> Unit = {
            appActionQueue.enqueue(AppAction.CreateMemo)
        }

        val requestOpenMemo: (String) -> Unit = { memoId ->
            if (memoId.isNotBlank()) {
                appActionQueue.enqueue(AppAction.OpenMemo(memoId))
            }
        }

        val requestFocusMemo: (String) -> Unit = { memoId ->
            if (memoId.isNotBlank()) {
                appActionQueue.enqueue(AppAction.FocusMemo(memoId))
            }
        }

        val requestFocusMemoInDefaultMainList: (String) -> Unit = { memoId ->
            if (memoId.isNotBlank()) {
                sidebarStateHolder.clearFilters()
                _memoListFilter.value = MemoListFilter()
                appActionQueue.enqueue(AppAction.FocusMemo(memoId))
            }
        }

        val consumeAppActionEvent: (Long) -> Unit = { eventId ->
            appActionQueue.consume(eventId)
        }

        internal fun requestPendingNewMemoCreation(
            content: String,
            geoLocation: String? = null,
            timestampMillis: Long? = null,
        ): Boolean {
            return pendingNewMemoCreationCoordinator
                .submit(content = content, geoLocation = geoLocation, timestampMillis = timestampMillis)
                ?.also { request ->
                    _pendingNewMemoCreationRequest.value = request
                } != null
        }

        internal val consumePendingNewMemoCreationRequest: (Long) -> PendingNewMemoCreationRequest? = { requestId ->
            pendingNewMemoCreationCoordinator.consume(requestId).also {
                _pendingNewMemoCreationRequest.value = pendingNewMemoCreationCoordinator.pendingRequest
            }
        }

        internal val cancelPendingNewMemoCreationRequest: (Long) -> Unit = { requestId ->
            pendingNewMemoCreationCoordinator.cancel(requestId)
            _pendingNewMemoCreationRequest.value = pendingNewMemoCreationCoordinator.pendingRequest
        }

        val createDefaultDirectories: (Boolean, Boolean) -> Unit = { forImage, forVoice ->
            viewModelScope.launch {
                runCatching {
                    workspaceCoordinator.createDefaultDirectories(forImage, forVoice)
                }.onFailure { throwable ->
                    if (throwable is kotlinx.coroutines.CancellationException) {
                        throw throwable
                    }
                    _errorMessage.value = throwable.toUserMessage("Failed to create directories")
                }
            }
        }

        val onDirectorySelected: (String) -> Unit = { path ->
            val shouldShowInitialImport = beginInitialImportIfNeeded()
            manualRootRefreshPath.set(path)
            viewModelScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            workspaceCoordinator.switchRootAndRefresh(path)
                        }.onFailure { throwable ->
                            handleRefreshFailure(
                                throwable = throwable,
                                fallbackMessage = "Failed to switch storage folder",
                            )
                        }
                    }
                } finally {
                    if (_rootDirectory.value != path) {
                        manualRootRefreshPath.compareAndSet(path, null)
                    }
                    endInitialImportIfNeeded(shouldShowInitialImport)
                }
            }
        }

        val onSearch: (String) -> Unit = { query ->
            sidebarStateHolder.updateSearchQuery(query)
        }

        val updateMemoSortOption: (MemoSortOption) -> Unit = { sortOption ->
            val current = _memoListFilter.value
            _memoListFilter.value =
                if (current.sortOption == sortOption) {
                    current.copy(sortAscending = !current.sortAscending)
                } else {
                    current.copy(sortOption = sortOption, sortAscending = true)
                }
        }

        val updateMemoStartDate: (LocalDate?) -> Unit = { startDate ->
            val current = _memoListFilter.value
            val adjustedEnd =
                current.endDate?.takeUnless { endDate ->
                    startDate != null && endDate.isBefore(startDate)
                }
            _memoListFilter.value = current.copy(startDate = startDate, endDate = adjustedEnd)
        }

        val updateMemoEndDate: (LocalDate?) -> Unit = { endDate ->
            val current = _memoListFilter.value
            val adjustedStart =
                current.startDate?.takeUnless { startDate ->
                    endDate != null && startDate.isAfter(endDate)
                }
            _memoListFilter.value = current.copy(startDate = adjustedStart, endDate = endDate)
        }

        val filterMemosByDate: (LocalDate) -> Unit = { date ->
            _memoListFilter.value = _memoListFilter.value.copy(startDate = date, endDate = date)
        }

        val clearMemoDateRange: () -> Unit = {
            _memoListFilter.value = _memoListFilter.value.copy(startDate = null, endDate = null)
        }

        val clearMemoListFilter: () -> Unit = {
            _memoListFilter.value = MemoListFilter()
        }

        val clearFilters: () -> Unit = {
            sidebarStateHolder.clearFilters()
        }

        val refresh: suspend () -> Unit = {
            withContext(Dispatchers.IO) {
                runCatching {
                    workspaceCoordinator.refreshMemos()
                }.onFailure { throwable ->
                    handleRefreshFailure(throwable = throwable, fallbackMessage = "Failed to refresh memos")
                }
            }
        }

        val resolveMemoById: suspend (String) -> Memo? = { memoId ->
            withContext(Dispatchers.IO) {
                memoUiCoordinator.getMemoById(memoId)
            }
        }

        val resolveDefaultMainListIndex: suspend (String) -> Int? = { memoId ->
            withContext(Dispatchers.IO) {
                memoUiCoordinator.getDefaultMainListIndex(memoId)
            }
        }

        val deleteMemo: (Memo) -> Unit = { memo ->
            viewModelScope.launch {
                val result =
                    runDeleteAnimationWithRollback(
                        itemId = memo.id,
                        deletingIds = _deletingMemoIds,
                    ) {
                        mainMemoMutationCoordinator.deleteMemo(memo)
                    }
                result.exceptionOrNull()?.let { throwable ->
                    _errorMessage.value = throwable.toUserMessage()
                }
            }
        }

        internal fun onPagedDeleteAnimationSettled(memoId: String) {
            _deletingMemoIds.value = _deletingMemoIds.value - memoId
        }

        val setMemoPinned: (Memo, Boolean) -> Unit = { memo, pinned ->
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    memoUiCoordinator.setMemoPinned(memo.id, pinned)
                }.onFailure { throwable ->
                    if (throwable is kotlinx.coroutines.CancellationException) {
                        throw throwable
                    }
                    _errorMessage.value = throwable.toUserMessage("Failed to update pin status")
                }
            }
        }

        val updateMemo: (Memo, Int, Boolean) -> Unit = { memo, lineIndex, checked ->
            viewModelScope.launch {
                runCatching {
                    mainMemoMutationCoordinator.toggleCheckboxLineAndUpdate(memo, lineIndex, checked)
                }.onFailure { throwable ->
                    if (throwable is kotlinx.coroutines.CancellationException) {
                        throw throwable
                    }
                    _errorMessage.value = throwable.toUserMessage("Failed to update todo")
                }
            }
        }

        val syncImageCacheNow: () -> Unit = {
            requestImageCacheSync("Failed to sync image cache")
        }

        private fun requestImageCacheSync(fallbackMessage: String) {
            if (imageCacheSyncJob?.isActive == true) {
                return
            }
            imageCacheSyncJob =
                viewModelScope.launch {
                    try {
                        runCatching {
                            workspaceCoordinator.syncImageCacheBestEffort()
                        }.onFailure { throwable ->
                            if (throwable is kotlinx.coroutines.CancellationException) {
                                throw throwable
                            }
                            _errorMessage.value = throwable.toUserMessage(fallbackMessage)
                        }
                    } finally {
                        imageCacheSyncJob = null
                    }
                }
        }

        val loadVersionHistory: (Memo) -> Unit = { memo ->
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    versionHistoryCoordinator.load(memo)
                }.onFailure { throwable ->
                    if (throwable is kotlinx.coroutines.CancellationException) {
                        throw throwable
                    }
                    Timber.w(throwable, "Failed to load version history")
                    versionHistoryCoordinator.hide()
                    _errorMessage.value = throwable.toUserMessage("Failed to load version history")
                }
            }
        }

        val loadMoreVersionHistory: () -> Unit = {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    versionHistoryCoordinator.loadMore()
                }.onFailure { throwable ->
                    if (throwable is kotlinx.coroutines.CancellationException) {
                        throw throwable
                    }
                    _errorMessage.value = throwable.toUserMessage("Failed to load more version history")
                }
            }
        }

        val restoreVersion: (Memo, MemoRevision) -> Unit = { memo, version ->
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    versionHistoryCoordinator.restore(memo, version)
                }.onFailure { throwable ->
                    if (throwable is kotlinx.coroutines.CancellationException) {
                        throw throwable
                    }
                    _errorMessage.value = throwable.toUserMessage("Failed to restore version")
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
            _errorMessage.value = null
        }

        suspend fun runDeferredStartupTasksIfNeeded() {
            if (!deferredStartupRequested.compareAndSet(false, true)) {
                return
            }
            if (!_hasResolvedInitialRoot.value) {
                _hasResolvedInitialRoot.filter { resolved -> resolved }.first()
            }
            runCatching {
                startupCoordinator.runDeferredStartupTasks(_rootDirectory.value)
            }.onFailure { throwable ->
                when (throwable) {
                    is CancellationException -> throw throwable
                    is com.lomo.domain.usecase.SyncConflictException ->
                        _syncConflictEvent.tryEmit(throwable.conflicts)
                    is Exception ->
                        _errorMessage.value =
                            throwable.toUserMessage("Failed to finish startup sync")
                    else -> throw throwable
                }
            }.also {
                imageCacheSyncReady.value = true
            }
        }

        private fun updateRootDirectoryUiState(directory: String?) {
            val previousDirectory = _rootDirectory.value
            if (_hasResolvedInitialRoot.value && directory != null && directory != previousDirectory) {
                beginInitialImportIfNeeded()
            }
            if (directory != previousDirectory) {
                lastAutomaticRefreshMark = null
            }
            _rootDirectory.value = directory
            _hasResolvedInitialRoot.value = true
            if (directory == null) {
                _isInitialDirectoryImporting.value = false
            }
        }

        @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
        private fun loadImageMap() {
            // Image map now provided by ImageMapProvider (P2-001 refactor)
            // No need to collect here - imageMap exposed directly from provider
            viewModelScope.launch {
                val initialConfiguredImageDirectory = appConfigStateProvider.currentImageDirectory()
                val gateInitialConfiguredDirectorySync = AtomicBoolean(initialConfiguredImageDirectory != null)

                imageDirectory
                    .filterNotNull()
                    .distinctUntilChanged()
                    .transformLatest { directory ->
                        val shouldWaitForDeferredStartup =
                            gateInitialConfiguredDirectorySync.get() &&
                                directory == initialConfiguredImageDirectory
                        if (shouldWaitForDeferredStartup) {
                            gateInitialConfiguredDirectorySync.set(false)
                            imageCacheSyncReady.filter { ready -> ready }.first()
                        }
                        emit(directory)
                    }.debounce(IMAGE_DIRECTORY_SYNC_DEBOUNCE_MILLIS)
                    .collect { requestImageCacheSync("Failed to sync image cache") }
            }
        }

        private suspend fun handleObservedRootDirectoryChange(directory: String?) {
            val previousDirectory = _rootDirectory.value
            updateRootDirectoryUiState(directory)
            val shouldRefreshForObservedRootChange =
                directory != null &&
                    directory != previousDirectory &&
                    !manualRootRefreshPath.compareAndSet(directory, null)
            if (shouldRefreshForObservedRootChange) {
                refreshForRootChange()
            }
        }

        internal val requestAutomaticRefreshForVisibleScreen: () -> Unit =
            refresh@{
                if (_rootDirectory.value == null) return@refresh
                if (automaticRefreshJob?.isActive == true) return@refresh
                if (!hasAutomaticRefreshCooldownElapsed(lastAutomaticRefreshMark)) return@refresh

                lastAutomaticRefreshMark = TimeSource.Monotonic.markNow()
                automaticRefreshJob =
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            runCatching {
                                workspaceCoordinator.refreshMemos()
                            }.onFailure { throwable ->
                                if (throwable is kotlinx.coroutines.CancellationException) {
                                    throw throwable
                                }
                                if (throwable is com.lomo.domain.usecase.SyncConflictException) {
                                    _syncConflictEvent.tryEmit(throwable.conflicts)
                                } else {
                                    Timber.w(throwable, "Automatic memo refresh failed")
                                }
                            }
                        } finally {
                            automaticRefreshJob = null
                        }
                    }
            }

        private suspend fun refreshForRootChange() {
            val shouldShowInitialImport = _isInitialDirectoryImporting.value
            try {
                withContext(Dispatchers.IO) {
                    runCatching {
                        workspaceCoordinator.rebuildCurrentWorkspace()
                    }.onFailure { throwable ->
                        handleRefreshFailure(
                            throwable = throwable,
                            fallbackMessage = "Failed to rebuild workspace",
                        )
                    }
                }
            } finally {
                endInitialImportIfNeeded(shouldShowInitialImport)
            }
        }

        private fun beginInitialImportIfNeeded(): Boolean {
            val shouldShowInitialImport = !_isInitialDirectoryImporting.value
            if (shouldShowInitialImport) {
                _isInitialDirectoryImporting.value = true
            }
            return shouldShowInitialImport
        }

        private fun endInitialImportIfNeeded(shouldShowInitialImport: Boolean) {
            if (shouldShowInitialImport) {
                _isInitialDirectoryImporting.value = false
            }
        }

        private fun handleRefreshFailure(
            throwable: Throwable,
            fallbackMessage: String,
        ) {
            when (throwable) {
                is kotlinx.coroutines.CancellationException -> throw throwable
                is com.lomo.domain.usecase.SyncConflictException ->
                    _syncConflictEvent.tryEmit(throwable.conflicts)
                else -> _errorMessage.value = throwable.toUserMessage(fallbackMessage)
            }
        }

        // processMemoContent moved to MemoUiMapper
    }

private fun hasAutomaticRefreshCooldownElapsed(lastAutomaticRefreshMark: TimeMark?): Boolean {
    val elapsedMillis = lastAutomaticRefreshMark?.elapsedNow()?.inWholeMilliseconds ?: return true
    return elapsedMillis >= AUTO_REFRESH_MIN_INTERVAL_MILLIS
}

data class MemoUiModel(
    val memo: Memo,
    val processedContent: String,
    val precomputedRenderPlan: com.lomo.ui.component.markdown.ModernMarkdownRenderPlan?,
    val tags: ImmutableList<String>,
    val imageUrls: ImmutableList<String> = persistentListOf(),
    val shouldShowExpand: Boolean = false,
    val collapsedSummary: String = "",
)
