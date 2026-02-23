package com.lomo.app.feature.main

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.memo.MemoFlowProcessor
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.feature.preferences.observeAppPreferences
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.SettingsRepository
import com.lomo.ui.util.stateInViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
        private val getFilteredMemosUseCase: com.lomo.domain.usecase.GetFilteredMemosUseCase,
        private val memoMutator: MainMemoMutator,
        private val startupCoordinator: MainStartupCoordinator,
        private val mediaCoordinator: MainMediaCoordinator,
    ) : ViewModel() {
        private val _updateUrl = MutableStateFlow<String?>(null)
        val updateUrl: StateFlow<String?> = _updateUrl
        private val _updateVersion = MutableStateFlow<String?>(null)
        val updateVersion: StateFlow<String?> = _updateVersion
        private val _updateReleaseNotes = MutableStateFlow<String?>(null)
        val updateReleaseNotes: StateFlow<String?> = _updateReleaseNotes

        fun checkForUpdates() {
            viewModelScope.launch {
                try {
                    val info = startupCoordinator.checkForUpdatesIfEnabled()
                    if (info != null) {
                        _updateUrl.value = info.url
                        _updateVersion.value = info.version
                        _updateReleaseNotes.value = info.releaseNotes
                    }
                } catch (_: Exception) {
                    // Ignore update check errors
                }
            }
        }

        fun dismissUpdateDialog() {
            _updateUrl.value = null
            _updateVersion.value = null
            _updateReleaseNotes.value = null
        }

        // ... existing code ...

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

        // Shared Content State
        sealed interface SharedContent {
            data class Text(
                val content: String,
            ) : SharedContent

            data class Image(
                val uri: android.net.Uri,
            ) : SharedContent
        }

        private val _sharedContent = MutableStateFlow<SharedContent?>(null)
        val sharedContent: StateFlow<SharedContent?> = _sharedContent

        fun handleSharedText(text: String) {
            _sharedContent.value = SharedContent.Text(text)
        }

        fun handleSharedImage(uri: android.net.Uri) {
            _sharedContent.value = SharedContent.Image(uri)
        }

        fun consumeSharedContent() {
            _sharedContent.value = null
        }

        private val _uiState = MutableStateFlow<MainScreenState>(MainScreenState.Loading)
        val uiState: StateFlow<MainScreenState> = _uiState

        private val deletingMemoIds = MutableStateFlow<Set<String>>(emptySet())

        private val _rootDirectory = MutableStateFlow<String?>(null)
        val rootDirectory: StateFlow<String?> = _rootDirectory

        val imageDirectory: StateFlow<String?> =
            repository
                .getImageDirectory()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        val imageMap: StateFlow<Map<String, android.net.Uri>> = imageMapProvider.imageMap

        val voiceDirectory: StateFlow<String?> =
            repository
                .getVoiceDirectory()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        fun createDefaultDirectories(
            forImage: Boolean,
            forVoice: Boolean,
        ) {
            viewModelScope.launch {
                mediaCoordinator.createDefaultDirectories(forImage, forVoice)
            }
        }

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val memos: StateFlow<List<Memo>> =
            combine(_searchQuery, _selectedTag) { query: String, tag: String? -> query to tag }
                .flatMapLatest { (query, tag) -> getFilteredMemosUseCase(query, tag) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

        fun addMemo(content: String) {
            if (_rootDirectory.value == null) {
                _errorMessage.value = "Please select a folder first"
                return
            }
            if (content.isBlank()) {
                _errorMessage.value = com.lomo.domain.validation.MemoContentValidator.EMPTY_CONTENT_MESSAGE
                return
            }
            if (content.length > com.lomo.domain.AppConfig.MAX_MEMO_LENGTH) {
                _errorMessage.value =
                    com.lomo.domain.validation.MemoContentValidator
                        .lengthExceededMessage()
                return
            }
            viewModelScope.launch {
                try {
                    memoMutator.addMemo(content)

                    // Bug 3: Memo saved, keep the images
                    mediaCoordinator.clearTrackedImages()

                    // Force refresh to pull new item (Sync might have already done it via Flow)
                    // repository.refreshMemos() // Auto-triggered by file observer usually
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
                try {
                    memoMutator.deleteMemo(memo)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.message
                } finally {
                    deletingMemoIds.value = deletingMemoIds.value - memo.id
                }
            }
        }

        fun updateMemo(
            memo: Memo,
            newContent: String,
        ) {
            viewModelScope.launch {
                try {
                    memoMutator.updateMemo(memo, newContent)

                    // Bug 3: Memo saved, keep the images
                    mediaCoordinator.clearTrackedImages()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.message
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
                    memoMutator.toggleCheckbox(memo, lineIndex, checked)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = "Failed to update todo: ${e.message}"
                }
            }
        }

        fun saveImage(
            uri: android.net.Uri,
            onResult: (String) -> Unit,
            onError: (() -> Unit)? = null,
        ) {
            viewModelScope.launch {
                try {
                    val path = mediaCoordinator.saveImageAndTrack(uri)
                    onResult(path)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = "Failed to save image: ${e.message}"
                    onError?.invoke()
                }
            }
        }

        fun discardInputs() {
            viewModelScope.launch {
                mediaCoordinator.discardTrackedImages()
            }
        }

        // Sidebar stats logic moved to SidebarViewModel

        init {
            // P1-002 Fix: Consolidated initialization to prevent race condition
            // First get initial value synchronously, then listen for subsequent updates
            viewModelScope.launch {
                // Step 1: Get initial value and set state immediately
                val initialDir = startupCoordinator.initializeRootDirectory()
                updateRootDirectoryUiState(initialDir)

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
                .onEach { path: String? ->
                    if (path != null) {
                        mediaCoordinator.syncImageCacheBestEffort()
                    }
                }.launchIn(viewModelScope)

            // Auto-refresh memos when root directory changes
            rootDirectory
                .onEach { path: String? ->
                    if (path != null) {
                        refresh()
                    }
                }.launchIn(viewModelScope)

            // Check for updates on startup, independent of root directory
            checkForUpdates()
        }

        fun syncImageCacheNow() {
            viewModelScope.launch {
                mediaCoordinator.syncImageCacheBestEffort()
            }
        }

        private val defaultPreferences = AppPreferencesState.defaults()

        val appPreferences: StateFlow<AppPreferencesState> =
            settingsRepository
                .observeAppPreferences()
                .stateInViewModel(viewModelScope, defaultPreferences)

        val activeDayCount: StateFlow<Int> =
            repository
                .getActiveDayCount()
                .stateIn(
                    scope = viewModelScope,
                    started =
                        kotlinx.coroutines.flow.SharingStarted
                            .WhileSubscribed(5000),
                    initialValue = 0,
                )

        fun clearError() {
            _errorMessage.value = null
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
