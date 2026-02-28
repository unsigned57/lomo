package com.lomo.app.feature.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.feature.preferences.activeDayCountState
import com.lomo.app.feature.preferences.appPreferencesState
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoVersion
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.usecase.InitializeWorkspaceUseCase
import com.lomo.domain.usecase.RefreshMemosUseCase
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
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
        private val refreshMemosUseCase: RefreshMemosUseCase,
        private val initializeWorkspaceUseCase: InitializeWorkspaceUseCase,
        private val mediaRepository: MediaRepository,
        private val startupCoordinator: MainStartupCoordinator,
    ) : ViewModel() {
        data class PendingEvent<T>(
            val id: Long,
            val payload: T,
        )

        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage

        val isSyncing: StateFlow<Boolean> =
            repository
                .isSyncing()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

        val searchQuery: StateFlow<String> = sidebarStateHolder.searchQuery
        val selectedTag: StateFlow<String?> = sidebarStateHolder.selectedTag

        sealed interface MainScreenState {
            data object Loading : MainScreenState

            data object NoDirectory : MainScreenState

            data class Ready(
                val hasData: Boolean,
            ) : MainScreenState
        }

        // Shared content is modeled as pending events with explicit consume semantics.
        sealed interface SharedContent {
            data class Text(
                val content: String,
            ) : SharedContent

            data class Image(
                val uri: android.net.Uri,
            ) : SharedContent
        }

        private var nextEventId: Long = 0L
        private fun nextPendingEventId(): Long {
            nextEventId += 1
            return nextEventId
        }

        private fun newSharedContentEvent(payload: SharedContent): PendingEvent<SharedContent> =
            PendingEvent(id = nextPendingEventId(), payload = payload)

        private fun newAppActionEvent(payload: AppAction): PendingEvent<AppAction> =
            PendingEvent(id = nextPendingEventId(), payload = payload)

        private val _sharedContentEvents = MutableStateFlow<List<PendingEvent<SharedContent>>>(emptyList())
        val sharedContentEvents: StateFlow<List<PendingEvent<SharedContent>>> = _sharedContentEvents

        fun handleSharedText(text: String) {
            _sharedContentEvents.update { events ->
                val pending = newSharedContentEvent(SharedContent.Text(text))
                (events + pending).takeLast(64)
            }
        }

        fun handleSharedImage(uri: android.net.Uri) {
            _sharedContentEvents.update { events ->
                val pending = newSharedContentEvent(SharedContent.Image(uri))
                (events + pending).takeLast(64)
            }
        }

        fun consumeSharedContentEvent(eventId: Long) {
            _sharedContentEvents.update { events -> events.filterNot { it.id == eventId } }
        }

        sealed interface AppAction {
            data object CreateMemo : AppAction

            data class OpenMemo(
                val memoId: String,
            ) : AppAction
        }

        private val _appActionEvents = MutableStateFlow<List<PendingEvent<AppAction>>>(emptyList())
        val appActionEvents: StateFlow<List<PendingEvent<AppAction>>> = _appActionEvents

        fun requestCreateMemo() {
            _appActionEvents.update { events ->
                val pending = newAppActionEvent(AppAction.CreateMemo)
                (events + pending).takeLast(64)
            }
        }

        fun requestOpenMemo(memoId: String) {
            if (memoId.isNotBlank()) {
                _appActionEvents.update { events ->
                    val pending = newAppActionEvent(AppAction.OpenMemo(memoId))
                    (events + pending).takeLast(64)
                }
            }
        }

        fun consumeAppActionEvent(eventId: Long) {
            _appActionEvents.update { events -> events.filterNot { it.id == eventId } }
        }

        private val _uiState = MutableStateFlow<MainScreenState>(MainScreenState.Loading)
        val uiState: StateFlow<MainScreenState> = _uiState

        private val deletingMemoIds = MutableStateFlow<Set<String>>(emptySet())

        private val _rootDirectory = MutableStateFlow<String?>(null)
        val rootDirectory: StateFlow<String?> = _rootDirectory
        private val visibleMemoIds = MutableStateFlow<Set<String>>(emptySet())

        val imageDirectory: StateFlow<String?> =
            appConfigRepository
                .getImageDirectory()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        val imageMap: StateFlow<Map<String, android.net.Uri>> = imageMapProvider.imageMap

        val voiceDirectory: StateFlow<String?> =
            appConfigRepository
                .getVoiceDirectory()
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
                    initializeWorkspaceUseCase.ensureDefaultMediaDirectories(forImage, forVoice)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.userMessage("Failed to create directories")
                }
            }
        }

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val memos: StateFlow<List<Memo>> =
            combine(searchQuery, selectedTag) { query: String, tag: String? -> query to tag }
                .flatMapLatest { (query, tag) -> resolveMemoFlow(query, tag) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
                combine(memos, rootDirectory, imageDirectory, imageMap, visibleMemoIds) {
                        currentMemos,
                        rootDir,
                        imageDir,
                        currentImageMap,
                        prioritizeIds,
                    ->
                    currentMemos to UiMemoMappingInput(
                        rootDirectory = rootDir,
                        imageDirectory = imageDir,
                        imageMap = currentImageMap,
                        prioritizedMemoIds = prioritizeIds,
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
            }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        fun onDirectorySelected(path: String) {
            viewModelScope.launch(Dispatchers.IO) {
                appConfigRepository.setRootDirectory(path)
                repository.refreshMemos()
            }
        }

        fun onSearch(query: String) {
            sidebarStateHolder.updateSearchQuery(query)
        }

        fun onTagSelected(tag: String?) {
            sidebarStateHolder.updateSelectedTag(tag)
        }

        fun clearFilters() {
            sidebarStateHolder.clearFilters()
        }

        suspend fun refresh() {
            withContext(Dispatchers.IO) {
                try {
                    refreshMemosUseCase()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.message
                }
            }
        }

        suspend fun resolveMemoById(memoId: String): Memo? {
            allMemos.value.firstOrNull { it.id == memoId }?.let { memo ->
                return memo
            }

            withContext(Dispatchers.IO) {
                runCatching { repository.refreshMemos() }
                    .onFailure { error ->
                        Timber.w(error, "Failed to refresh memos for id=%s", memoId)
                    }
            }
            return allMemos.value.firstOrNull { it.id == memoId }
        }

        fun deleteMemo(memo: Memo) {
            viewModelScope.launch {
                deletingMemoIds.value = deletingMemoIds.value + memo.id
                kotlinx.coroutines.delay(300L) // Wait for fade out animation
                var deleted = false
                try {
                    mainMemoMutationUseCase.deleteMemo(memo)
                    deleted = true
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.userMessage()
                } finally {
                    if (!deleted) {
                        deletingMemoIds.value = deletingMemoIds.value - memo.id
                    }
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
                    _errorMessage.value = e.userMessage("Failed to update todo")
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
                    MainScreenState.Ready(hasData = true)
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
                        syncImageCacheBestEffort()
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
                    syncImageCacheBestEffort()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.userMessage("Failed to sync image cache")
                }
            }
        }

        val appPreferences: StateFlow<AppPreferencesState> =
            appConfigRepository.appPreferencesState(viewModelScope)

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
                    _errorMessage.value = e.userMessage("Failed to load version history")
                }
            }
        }

        fun restoreVersion(memo: Memo, version: MemoVersion) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    versionHistoryCoordinator.restore(memo, version)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.userMessage("Failed to restore version")
                }
            }
        }

        fun dismissVersionHistory() {
            versionHistoryCoordinator.hide()
        }

        fun clearError() {
            _errorMessage.value = null
        }

        fun updateVisibleMemoIds(ids: Set<String>) {
            if (visibleMemoIds.value != ids) {
                visibleMemoIds.value = ids
            }
        }

        private fun resolveMemoFlow(
            query: String,
            tag: String?,
        ): Flow<List<Memo>> =
            when {
                !tag.isNullOrBlank() -> repository.getMemosByTagList(tag)
                query.isNotBlank() -> repository.searchMemosList(query)
                else -> allMemos
            }

        private fun Throwable.userMessage(prefix: String? = null): String =
            when {
                prefix.isNullOrBlank() && message.isNullOrBlank() -> "Unexpected error"
                prefix.isNullOrBlank() -> message.orEmpty()
                message.isNullOrBlank() -> prefix
                else -> "$prefix: $message"
            }

        private suspend fun syncImageCacheBestEffort() {
            try {
                mediaRepository.syncImageCache()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best-effort background sync.
            }
        }

        private data class UiMemoMappingInput(
            val rootDirectory: String?,
            val imageDirectory: String?,
            val imageMap: Map<String, android.net.Uri>,
            val prioritizedMemoIds: Set<String>,
        )

        // processMemoContent moved to MemoUiMapper
    }

data class MemoUiModel(
    val memo: Memo,
    val processedContent: String,
    val markdownNode: com.lomo.ui.component.markdown.ImmutableNode?,
    val tags: ImmutableList<String>,
    val imageUrls: ImmutableList<String> = persistentListOf(),
    val isDeleting: Boolean = false,
)
