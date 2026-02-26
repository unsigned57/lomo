package com.lomo.app.feature.main

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.memo.MemoFlowProcessor
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.feature.preferences.activeDayCountState
import com.lomo.app.feature.preferences.appPreferencesState
import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.repository.AppWidgetRepository
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.SettingsRepository
import com.lomo.domain.validation.MemoContentValidator
import com.lomo.ui.component.navigation.SidebarStats
import com.lomo.ui.component.navigation.SidebarTag
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        private val repository: MemoRepository,
        private val settingsRepository: SettingsRepository,
        private val savedStateHandle: SavedStateHandle,
        private val memoFlowProcessor: MemoFlowProcessor,
        private val imageMapProvider: ImageMapProvider,
        private val memoContentValidator: MemoContentValidator,
        private val mainMediaCoordinator: MainMediaCoordinator,
        private val appWidgetRepository: AppWidgetRepository,
        private val textProcessor: MemoTextProcessor,
        private val startupCoordinator: MainStartupCoordinator,
    ) : ViewModel() {
        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage

        val isSyncing: StateFlow<Boolean> =
            repository
                .isSyncing()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

        private val _searchQuery = savedStateHandle.getStateFlow(KEY_SEARCH_QUERY, "")
        val searchQuery: StateFlow<String> = _searchQuery

        private val _selectedTag = savedStateHandle.getStateFlow<String?>(KEY_SELECTED_TAG, null)
        val selectedTag: StateFlow<String?> = _selectedTag

        sealed interface MainScreenState {
            data object Loading : MainScreenState

            data object NoDirectory : MainScreenState

            data class Ready(
                val hasData: Boolean,
            ) : MainScreenState
        }

        // Shared content is modeled as one-shot events to avoid UI -> ViewModel consume round-trips.
        sealed interface SharedContent {
            data class Text(
                val content: String,
            ) : SharedContent

            data class Image(
                val uri: android.net.Uri,
            ) : SharedContent
        }

        private val sharedContentEventsChannel = Channel<SharedContent>(capacity = Channel.BUFFERED)
        val sharedContentEvents = sharedContentEventsChannel.receiveAsFlow()

        fun handleSharedText(text: String) {
            sharedContentEventsChannel.trySend(SharedContent.Text(text))
        }

        fun handleSharedImage(uri: android.net.Uri) {
            sharedContentEventsChannel.trySend(SharedContent.Image(uri))
        }

        private val _uiState = MutableStateFlow<MainScreenState>(MainScreenState.Loading)
        val uiState: StateFlow<MainScreenState> = _uiState

        private val deletingMemoIds = MutableStateFlow<Set<String>>(emptySet())

        private val _rootDirectory = MutableStateFlow<String?>(null)
        val rootDirectory: StateFlow<String?> = _rootDirectory

        val imageDirectory: StateFlow<String?> =
            settingsRepository
                .getImageDirectory()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        val imageMap: StateFlow<Map<String, android.net.Uri>> = imageMapProvider.imageMap

        val voiceDirectory: StateFlow<String?> =
            settingsRepository
                .getVoiceDirectory()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        val allMemos: StateFlow<List<Memo>> =
            repository
                .getAllMemosList()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        data class SidebarUiState(
            val stats: SidebarStats = SidebarStats(),
            val memoCountByDate: Map<LocalDate, Int> = emptyMap(),
            val tags: List<SidebarTag> = emptyList(),
        )

        val sidebarUiState: StateFlow<SidebarUiState> =
            combine(
                repository.getMemoCountFlow(),
                repository.getMemoTimestampsFlow(),
                repository.getTagCountsFlow(),
            ) { memoCount, timestamps, tagCounts ->
                val zoneId = ZoneId.systemDefault()
                val memoCountByDate =
                    timestamps
                        .asSequence()
                        .map { timestamp ->
                            Instant
                                .ofEpochMilli(timestamp)
                                .atZone(zoneId)
                                .toLocalDate()
                        }.groupingBy { it }
                        .eachCount()

                SidebarUiState(
                    stats =
                        SidebarStats(
                            memoCount = memoCount,
                            tagCount = tagCounts.size,
                            dayCount = memoCountByDate.size,
                        ),
                    memoCountByDate = memoCountByDate,
                    tags =
                        tagCounts
                            .sortedWith(compareByDescending<com.lomo.domain.repository.MemoTagCount> { it.count }.thenBy { it.name })
                            .map { tagCount -> SidebarTag(name = tagCount.name, count = tagCount.count) },
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SidebarUiState())

        fun createDefaultDirectories(
            forImage: Boolean,
            forVoice: Boolean,
        ) {
            viewModelScope.launch {
                try {
                    mainMediaCoordinator.createDefaultDirectories(forImage, forVoice)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.userMessage("Failed to create directories")
                }
            }
        }

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val memos: StateFlow<List<Memo>> =
            combine(_searchQuery, _selectedTag) { query: String, tag: String? -> query to tag }
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
                memoFlowProcessor.mapMemoFlow(
                    memos = memos,
                    rootDirectory = rootDirectory,
                    imageDirectory = imageDirectory,
                    imageMap = imageMap,
                ),
                deletingMemoIds,
            ) { uiModels, deletingIds ->
                uiModels.map { uiModel ->
                    uiModel.copy(isDeleting = deletingIds.contains(uiModel.memo.id))
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        fun onDirectorySelected(path: String) {
            viewModelScope.launch {
                settingsRepository.setRootDirectory(path)
                repository.refreshMemos()
            }
        }

        fun onSearch(query: String) {
            savedStateHandle[KEY_SEARCH_QUERY] = query
        }

        fun onTagSelected(tag: String?) {
            val newTag = if (_selectedTag.value == tag) null else tag
            savedStateHandle[KEY_SELECTED_TAG] = newTag
        }

        fun clearFilters() {
            savedStateHandle[KEY_SEARCH_QUERY] = ""
            savedStateHandle[KEY_SELECTED_TAG] = null
        }

        fun refresh() {
            viewModelScope.launch {
                try {
                    repository.refreshMemos()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.message
                }
            }
        }

        fun deleteMemo(memo: Memo) {
            viewModelScope.launch {
                deletingMemoIds.value = deletingMemoIds.value + memo.id
                kotlinx.coroutines.delay(300L) // Wait for fade out animation
                var deleted = false
                try {
                    repository.deleteMemo(memo)
                    appWidgetRepository.updateAllWidgets()
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
                    val newContent = textProcessor.toggleCheckbox(memo.content, lineIndex, checked)
                    if (newContent != memo.content) {
                        memoContentValidator.validateForUpdate(newContent)
                        repository.updateMemo(memo, newContent)
                    }
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
                        try {
                            mainMediaCoordinator.syncImageCacheBestEffort()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Best-effort background sync.
                        }
                    }
                }.launchIn(viewModelScope)

            // Auto-refresh memos when root directory changes
            rootDirectory
                .onEach { path: String? ->
                    if (path != null) {
                        refresh()
                    }
                }.launchIn(viewModelScope)
        }

        fun syncImageCacheNow() {
            viewModelScope.launch {
                try {
                    mainMediaCoordinator.syncImageCacheBestEffort()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.userMessage("Failed to sync image cache")
                }
            }
        }

        val appPreferences: StateFlow<AppPreferencesState> =
            settingsRepository.appPreferencesState(viewModelScope)

        val activeDayCount: StateFlow<Int> =
            repository.activeDayCountState(viewModelScope)

        fun clearError() {
            _errorMessage.value = null
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

        companion object {
            private const val KEY_SEARCH_QUERY = "search_query"
            private const val KEY_SELECTED_TAG = "selected_tag"
        }

        // processMemoContent moved to MemoUiMapper
    }

data class MemoUiModel(
    val memo: Memo,
    val processedContent: String,
    val markdownNode: com.lomo.ui.component.markdown.ImmutableNode,
    val tags: ImmutableList<String>,
    val imageUrls: ImmutableList<String> = persistentListOf(),
    val isDeleting: Boolean = false,
)
