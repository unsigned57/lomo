package com.lomo.app.feature.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.runDeleteAnimationWithRollback
import com.lomo.app.feature.common.toUserMessage
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.feature.preferences.activeDayCountState
import com.lomo.app.feature.preferences.appPreferencesState
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoSortOption
import com.lomo.domain.model.MemoVersion
import com.lomo.domain.model.StorageArea
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.usecase.ApplyMainMemoFilterUseCase
import com.lomo.domain.usecase.ResolveMainMemoQueryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        private val repository: MemoRepository,
        private val appConfigRepository: AppConfigRepository,
        private val sidebarStateHolder: MainSidebarStateHolder,
        private val versionHistoryCoordinator: MainVersionHistoryCoordinator,
        private val memoUiMapper: MemoUiMapper,
        private val imageMapProvider: ImageMapProvider,
        private val mainMemoMutationUseCase: MainMemoMutationUseCase,
        private val workspaceCoordinator: MainWorkspaceCoordinator,
        private val startupCoordinator: MainStartupCoordinator,
        private val resolveMainMemoQueryUseCase: ResolveMainMemoQueryUseCase,
    ) : ViewModel() {
        private val applyMainMemoFilterUseCase = ApplyMainMemoFilterUseCase()
        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage

        val isSyncing: StateFlow<Boolean> =
            repository
                .isSyncing()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

        val searchQuery: StateFlow<String> = sidebarStateHolder.searchQuery
        val selectedTag: StateFlow<String?> = sidebarStateHolder.selectedTag
        private val _memoListFilter = MutableStateFlow(MemoListFilter())
        val memoListFilter: StateFlow<MemoListFilter> = _memoListFilter

        sealed interface MainScreenState {
            data object Loading : MainScreenState

            data object NoDirectory : MainScreenState

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

        fun handleSharedText(text: String) {
            sharedContentQueue.enqueue(SharedContent.Text(text))
        }

        fun handleSharedImage(uri: android.net.Uri) {
            pendingSharedImageQueue.enqueue(uri)
        }

        fun consumeSharedContentEvent(eventId: Long) {
            sharedContentQueue.consume(eventId)
        }

        fun consumePendingSharedImageEvent(eventId: Long) {
            pendingSharedImageQueue.consume(eventId)
        }

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

        fun requestCreateMemo() {
            appActionQueue.enqueue(AppAction.CreateMemo)
        }

        fun requestOpenMemo(memoId: String) {
            if (memoId.isNotBlank()) {
                appActionQueue.enqueue(AppAction.OpenMemo(memoId))
            }
        }

        fun requestFocusMemo(memoId: String) {
            if (memoId.isNotBlank()) {
                appActionQueue.enqueue(AppAction.FocusMemo(memoId))
            }
        }

        fun consumeAppActionEvent(eventId: Long) {
            appActionQueue.consume(eventId)
        }

        private val _uiState = MutableStateFlow<MainScreenState>(MainScreenState.Loading)
        val uiState: StateFlow<MainScreenState> = _uiState

        private val deletingMemoIds = MutableStateFlow<Set<String>>(emptySet())

        private val _rootDirectory = MutableStateFlow<String?>(null)
        val rootDirectory: StateFlow<String?> = _rootDirectory

        val imageDirectory: StateFlow<String?> =
            appConfigRepository
                .observeLocation(StorageArea.IMAGE)
                .map { it?.raw }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        val imageMap: StateFlow<Map<String, android.net.Uri>> = imageMapProvider.imageMap

        val voiceDirectory: StateFlow<String?> =
            appConfigRepository
                .observeLocation(StorageArea.VOICE)
                .map { it?.raw }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        val allMemos: StateFlow<List<Memo>> =
            repository
                .getAllMemosList()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        fun createDefaultDirectories(
            forImage: Boolean,
            forVoice: Boolean,
        ) {
            viewModelScope.launch {
                try {
                    workspaceCoordinator.createDefaultDirectories(forImage, forVoice)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.toUserMessage("Failed to create directories")
                }
            }
        }

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val memos: StateFlow<List<Memo>> =
            combine(searchQuery, selectedTag, memoListFilter) { query: String, tag: String?, filter: MemoListFilter ->
                MemoQueryInput(query = query, tag = tag, filter = filter.toEffectiveDateRangeFilter())
            }.flatMapLatest { input ->
                resolveMemoFlow(query = input.query, tag = input.tag)
                    .map { sourceMemos ->
                        applyMainMemoFilterUseCase(memos = sourceMemos, filter = input.filter)
                    }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        init {
            // Keep deleting flags only for rows that still exist in current list.
            // This avoids alpha bounce-back when DB removal arrives a little later.
            memos
                .onEach { list ->
                    val existingIds = list.asSequence().map { it.id }.toSet()
                    deletingMemoIds.value = deletingMemoIds.value.intersect(existingIds)
                }.launchIn(viewModelScope)
        }

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val uiMemos: StateFlow<List<MemoUiModel>> =
            combine(
                combine(memos, rootDirectory, imageDirectory, imageMap) {
                    currentMemos,
                    rootDir,
                    imageDir,
                    currentImageMap,
                    ->
                    currentMemos to
                        UiMemoMappingInput(
                            rootDirectory = rootDir,
                            imageDirectory = imageDir,
                            imageMap = currentImageMap,
                            prioritizedMemoIds = emptySet(),
                        )
                }.distinctUntilChanged()
                    .mapLatest { (currentMemos, input) ->
                        memoUiMapper.mapToUiModels(
                            memos = currentMemos,
                            rootPath = input.rootDirectory,
                            imagePath = input.imageDirectory,
                            imageMap = input.imageMap,
                            prioritizedMemoIds = input.prioritizedMemoIds,
                        )
                    },
                deletingMemoIds,
            ) { uiModels, deletingIds ->
                uiModels.map { uiModel ->
                    uiModel.copy(isDeleting = uiModel.memo.id in deletingIds)
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val galleryUiMemos: StateFlow<List<MemoUiModel>> =
            combine(
                combine(allMemos, rootDirectory, imageDirectory, imageMap) {
                    currentMemos,
                    rootDir,
                    imageDir,
                    currentImageMap,
                    ->
                    currentMemos
                        .asSequence()
                        .filter { memo -> memo.imageUrls.isNotEmpty() }
                        .toList() to
                        UiMemoMappingInput(
                            rootDirectory = rootDir,
                            imageDirectory = imageDir,
                            imageMap = currentImageMap,
                            prioritizedMemoIds = emptySet(),
                        )
                }.distinctUntilChanged()
                    .mapLatest { (currentMemos, input) ->
                        memoUiMapper.mapToUiModels(
                            memos = currentMemos,
                            rootPath = input.rootDirectory,
                            imagePath = input.imageDirectory,
                            imageMap = input.imageMap,
                            prioritizedMemoIds = input.prioritizedMemoIds,
                        )
                    },
                deletingMemoIds,
            ) { uiModels, deletingIds ->
                uiModels
                    .asSequence()
                    .filter { uiModel -> uiModel.imageUrls.isNotEmpty() }
                    .map { uiModel -> uiModel.copy(isDeleting = uiModel.memo.id in deletingIds) }
                    .sortedByDescending { uiModel -> uiModel.memo.timestamp }
                    .toList()
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        fun onDirectorySelected(path: String) {
            viewModelScope.launch(Dispatchers.IO) {
                workspaceCoordinator.switchRootAndRefresh(path)
            }
        }

        fun onSearch(query: String) {
            sidebarStateHolder.updateSearchQuery(query)
        }

        fun onTagSelected(tag: String?) {
            sidebarStateHolder.updateSelectedTag(tag)
        }

        fun updateMemoSortOption(sortOption: MemoSortOption) {
            val current = _memoListFilter.value
            _memoListFilter.value =
                if (current.sortOption == sortOption) {
                    current.copy(sortAscending = !current.sortAscending)
                } else {
                    current.copy(sortOption = sortOption, sortAscending = true)
                }
        }

        fun updateMemoStartDate(startDate: LocalDate?) {
            val current = _memoListFilter.value
            val adjustedEnd =
                current.endDate?.takeUnless { endDate ->
                    startDate != null && endDate.isBefore(startDate)
                }
            _memoListFilter.value = current.copy(startDate = startDate, endDate = adjustedEnd)
        }

        fun updateMemoEndDate(endDate: LocalDate?) {
            val current = _memoListFilter.value
            val adjustedStart =
                current.startDate?.takeUnless { startDate ->
                    endDate != null && startDate.isAfter(endDate)
                }
            _memoListFilter.value = current.copy(startDate = adjustedStart, endDate = endDate)
        }

        fun filterMemosByDate(date: LocalDate) {
            _memoListFilter.value = _memoListFilter.value.copy(startDate = date, endDate = date)
        }

        fun clearMemoDateRange() {
            _memoListFilter.value = _memoListFilter.value.copy(startDate = null, endDate = null)
        }

        fun clearMemoListFilter() {
            _memoListFilter.value = MemoListFilter()
        }

        fun clearFilters() {
            sidebarStateHolder.clearFilters()
        }

        suspend fun refresh() {
            withContext(Dispatchers.IO) {
                try {
                    workspaceCoordinator.refreshMemos()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.toUserMessage("Failed to refresh memos")
                }
            }
        }

        suspend fun resolveMemoById(memoId: String): Memo? {
            allMemos.value.firstOrNull { it.id == memoId }?.let { memo ->
                return memo
            }

            return withContext(Dispatchers.IO) {
                repository.getMemoById(memoId)
            }
        }

        fun deleteMemo(memo: Memo) {
            viewModelScope.launch {
                val result =
                    runDeleteAnimationWithRollback(
                        itemId = memo.id,
                        deletingIds = deletingMemoIds,
                    ) {
                        mainMemoMutationUseCase.deleteMemo(memo)
                    }
                result.exceptionOrNull()?.let { throwable ->
                    _errorMessage.value = throwable.toUserMessage()
                }
            }
        }

        fun setMemoPinned(
            memo: Memo,
            pinned: Boolean,
        ) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    repository.setMemoPinned(memo.id, pinned)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.toUserMessage("Failed to update pin status")
                }
            }
        }

        fun updateMemo(
            memo: Memo,
            lineIndex: Int,
            checked: Boolean,
        ) {
            viewModelScope.launch {
                try {
                    mainMemoMutationUseCase.toggleCheckboxLineAndUpdate(memo, lineIndex, checked)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.toUserMessage("Failed to update todo")
                }
            }
        }

        init {
            // P1-002 Fix: Consolidated initialization to prevent race condition
            // First get initial value synchronously, then listen for subsequent updates
            viewModelScope.launch {
                // Step 1: Get initial value and set state immediately
                val initialDir = startupCoordinator.initializeRootDirectory()
                updateRootDirectoryUiState(initialDir)

                // Step 1.5: Delay non-critical startup warmups to avoid blocking first render.
                viewModelScope.launch {
                    kotlinx.coroutines.delay(350L)
                    startupCoordinator.runDeferredStartupTasks(initialDir)
                }

                // Step 2: Listen for subsequent updates (drop first to avoid duplicate)
                startupCoordinator
                    .observeRootDirectoryChanges()
                    .collect { dir ->
                        updateRootDirectoryUiState(dir)
                    }
            }
            // Voice directory collector - pass to AudioPlayerManager for voice file resolution
            startupCoordinator.observeVoiceDirectoryChanges().launchIn(viewModelScope)

            // Initial image load
            loadImageMap()
        }

        private fun updateRootDirectoryUiState(directory: String?) {
            _rootDirectory.value = directory
            _uiState.value =
                if (directory == null) {
                    MainScreenState.NoDirectory
                } else {
                    MainScreenState.Ready
                }
        }

        private fun loadImageMap() {
            // Image map now provided by ImageMapProvider (P2-001 refactor)
            // No need to collect here - imageMap exposed directly from provider

            // Trigger sync in background whenever image directory changes
            imageDirectory
                .drop(1)
                .onEach { path: String? ->
                    if (path != null) {
                        workspaceCoordinator.syncImageCacheBestEffort()
                    }
                }.launchIn(viewModelScope)

            // Auto-refresh memos when root directory changes
            rootDirectory
                .drop(1)
                .distinctUntilChanged()
                .onEach { path: String? ->
                    if (path != null) {
                        refresh()
                    }
                }.launchIn(viewModelScope)
        }

        fun syncImageCacheNow() {
            viewModelScope.launch {
                try {
                    workspaceCoordinator.syncImageCacheBestEffort()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.toUserMessage("Failed to sync image cache")
                }
            }
        }

        val appPreferences: StateFlow<AppPreferencesState> =
            appConfigRepository.appPreferencesState(viewModelScope)

        val appLockEnabled: StateFlow<Boolean?> =
            appConfigRepository
                .isAppLockEnabled()
                .map<Boolean, Boolean?> { it }
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)

        val activeDayCount: StateFlow<Int> =
            repository.activeDayCountState(viewModelScope)

        val gitSyncEnabled: StateFlow<Boolean> =
            versionHistoryCoordinator
                .syncEnabled()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

        val versionHistoryState: StateFlow<MainVersionHistoryState> = versionHistoryCoordinator.state

        fun loadVersionHistory(memo: Memo) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    versionHistoryCoordinator.load(memo)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Failed to load version history")
                    versionHistoryCoordinator.hide()
                    _errorMessage.value = e.toUserMessage("Failed to load version history")
                }
            }
        }

        fun restoreVersion(
            memo: Memo,
            version: MemoVersion,
        ) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    versionHistoryCoordinator.restore(memo, version)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.toUserMessage("Failed to restore version")
                }
            }
        }

        fun dismissVersionHistory() {
            versionHistoryCoordinator.hide()
        }

        fun clearError() {
            _errorMessage.value = null
        }

        private fun resolveMemoFlow(
            query: String,
            tag: String?,
        ): Flow<List<Memo>> =
            when (val resolvedQuery = resolveMainMemoQueryUseCase(query = query, selectedTag = tag)) {
                is ResolveMainMemoQueryUseCase.ResolvedQuery.ByTag -> repository.getMemosByTagList(resolvedQuery.tag)
                is ResolveMainMemoQueryUseCase.ResolvedQuery.BySearchText -> repository.searchMemosList(resolvedQuery.query)
                ResolveMainMemoQueryUseCase.ResolvedQuery.AllMemos -> allMemos
            }

        private data class UiMemoMappingInput(
            val rootDirectory: String?,
            val imageDirectory: String?,
            val imageMap: Map<String, android.net.Uri>,
            val prioritizedMemoIds: Set<String>,
        )

        private data class MemoQueryInput(
            val query: String,
            val tag: String?,
            val filter: MemoListFilter,
        )

        /**
         * Expand one-sided date selection into an open range to keep filtering semantics:
         * - start only => [start, +infinity)
         * - end only => (-infinity, end]
         */
        private fun MemoListFilter.toEffectiveDateRangeFilter(): MemoListFilter {
            if (startDate != null && endDate != null) return this
            if (startDate == null && endDate == null) return this

            val effectiveStart = startDate ?: OPEN_RANGE_START
            val effectiveEnd = endDate ?: OPEN_RANGE_END
            return copy(startDate = effectiveStart, endDate = effectiveEnd)
        }

        // processMemoContent moved to MemoUiMapper

        private companion object {
            private val OPEN_RANGE_START: LocalDate = LocalDate.MIN
            private val OPEN_RANGE_END: LocalDate = LocalDate.MAX
        }
    }

data class MemoUiModel(
    val memo: Memo,
    val processedContent: String,
    val markdownNode: com.lomo.ui.component.markdown.ImmutableNode?,
    val tags: ImmutableList<String>,
    val imageUrls: ImmutableList<String> = persistentListOf(),
    val shouldShowExpand: Boolean = false,
    val collapsedSummary: String = "",
    val isDeleting: Boolean = false,
)
