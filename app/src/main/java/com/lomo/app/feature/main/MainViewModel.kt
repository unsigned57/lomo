package com.lomo.app.feature.main

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.BuildConfig
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.feature.preferences.observeAppPreferences
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.SettingsRepository
import com.lomo.domain.repository.WidgetRepository
import com.lomo.ui.util.stateInViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        private val repository: MemoRepository,
        private val settingsRepository: SettingsRepository,
        private val mediaRepository: com.lomo.domain.repository.MediaRepository,
        private val dataStore: com.lomo.data.local.datastore.LomoDataStore,
        private val savedStateHandle: SavedStateHandle,
        val mapper: MemoUiMapper,
        private val imageMapProvider: ImageMapProvider,
        private val textProcessor: com.lomo.data.util.MemoTextProcessor,
        private val getFilteredMemosUseCase: com.lomo.domain.usecase.GetFilteredMemosUseCase,
        private val widgetRepository: WidgetRepository,
        private val audioPlayerManager: com.lomo.ui.media.AudioPlayerManager,
        private val updateManager: com.lomo.app.feature.update.UpdateManager,
        private val createMemoUseCase: com.lomo.domain.usecase.CreateMemoUseCase,
        private val deleteMemoUseCase: com.lomo.domain.usecase.DeleteMemoUseCase,
        private val updateMemoUseCase: com.lomo.domain.usecase.UpdateMemoUseCase,
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
                    // First check if enabled
                    val enabled = settingsRepository.isCheckUpdatesOnStartupEnabled().first()
                    if (enabled) {
                        val info = updateManager.checkForUpdatesInfo()
                        if (info != null) {
                            _updateUrl.value = info.htmlUrl
                            _updateVersion.value = info.version
                            _updateReleaseNotes.value = info.releaseNotes
                        }
                    }
                } catch (e: Exception) {
                    // Ignore update check errors
                    e.printStackTrace()
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

        // Bug 3: Track filenames of images added during the current edit session.
        // If the user discards the input, we delete these files.
        private val ephemeralImageFilenames = mutableSetOf<String>()

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
                if (forImage) {
                    mediaRepository.createDefaultImageDirectory()
                }
                if (forVoice) {
                    mediaRepository.createDefaultVoiceDirectory()
                }
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
                memos,
                rootDirectory,
                imageDirectory,
                imageMap,
                deletingMemoIds,
            ) { currentMemos, rootDir, imageDir, currentImageMap, deletingIds ->
                MemoMappingBundle(
                    memos = currentMemos,
                    rootDirectory = rootDir,
                    imageDirectory = imageDir,
                    imageMap = currentImageMap,
                    deletingIds = deletingIds,
                )
            }.mapLatest { bundle ->
                mapper
                    .mapToUiModels(
                        memos = bundle.memos,
                        rootPath = bundle.rootDirectory,
                        imagePath = bundle.imageDirectory,
                        imageMap = bundle.imageMap,
                    ).map { uiModel ->
                        uiModel.copy(isDeleting = bundle.deletingIds.contains(uiModel.memo.id))
                    }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        private data class MemoMappingBundle(
            val memos: List<Memo>,
            val rootDirectory: String?,
            val imageDirectory: String?,
            val imageMap: Map<String, android.net.Uri>,
            val deletingIds: Set<String>,
        )

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
                    createMemoUseCase(content)
                    // Update widget after adding memo
                    widgetRepository.updateAllWidgets()

                    // Bug 3: Memo saved, keep the images
                    ephemeralImageFilenames.clear()

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
                    deleteMemoUseCase(memo)
                    // Update widget after deleting memo
                    widgetRepository.updateAllWidgets()
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
                    updateMemoUseCase(memo, newContent)
                    // Update widget after updating memo
                    widgetRepository.updateAllWidgets()

                    // Bug 3: Memo saved, keep the images
                    ephemeralImageFilenames.clear()
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
                    val newContent = textProcessor.toggleCheckbox(memo.content, lineIndex, checked)
                    if (newContent != memo.content) {
                        updateMemoUseCase(memo, newContent)
                    }
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
                    val path = mediaRepository.saveImage(uri)
                    // Track it for Bug 3 cleanup
                    ephemeralImageFilenames.add(path)

                    // Sync image cache immediately so new image is available for display
                    mediaRepository.syncImageCache()
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
            // Bug 3: User abandoned input, delete session images
            val toDelete = ephemeralImageFilenames.toList()
            ephemeralImageFilenames.clear()

            viewModelScope.launch {
                toDelete.forEach { filename ->
                    try {
                        mediaRepository.deleteImage(filename)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                mediaRepository.syncImageCache()
            }
        }

        // Sidebar stats logic moved to SidebarViewModel

        init {
            // P1-002 Fix: Consolidated initialization to prevent race condition
            // First get initial value synchronously, then listen for subsequent updates
            viewModelScope.launch {
                // Step 1: Get initial value and set state immediately
                val initialDir = repository.getRootDirectoryOnce()
                _rootDirectory.value = initialDir
                audioPlayerManager.setRootDirectory(initialDir)
                _uiState.value =
                    if (initialDir == null) {
                        MainScreenState.NoDirectory
                    } else {
                        MainScreenState.Ready(hasData = true)
                    }

                resyncCachesIfAppVersionChanged(initialDir)

                // Step 2: Listen for subsequent updates (drop first to avoid duplicate)
                repository
                    .getRootDirectory()
                    .drop(1)
                    .collect { dir ->
                        _rootDirectory.value = dir
                        audioPlayerManager.setRootDirectory(dir)
                        _uiState.value =
                            if (dir == null) {
                                MainScreenState.NoDirectory
                            } else {
                                MainScreenState.Ready(hasData = true)
                            }
                    }
            }

            // Voice directory collector - pass to AudioPlayerManager for voice file resolution
            viewModelScope.launch {
                repository.getVoiceDirectory().collect { voiceDir ->
                    audioPlayerManager.setVoiceDirectory(voiceDir)
                }
            }

            // Initial image load
            loadImageMap()
        }

        private fun loadImageMap() {
            // Image map now provided by ImageMapProvider (P2-001 refactor)
            // No need to collect here - imageMap exposed directly from provider

            // Trigger sync in background whenever image directory changes
            imageDirectory
                .onEach { path: String? ->
                    if (path != null) {
                        mediaRepository.syncImageCache()
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
                try {
                    mediaRepository.syncImageCache()
                } catch (_: Exception) {
                    // Best effort refresh.
                }
            }
        }

        private suspend fun resyncCachesIfAppVersionChanged(rootDir: String?) {
            val currentVersion = "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"
            val lastVersion = dataStore.getLastAppVersionOnce()
            if (lastVersion == currentVersion) return

            if (rootDir != null) {
                try {
                    repository.refreshMemos()
                } catch (_: Exception) {
                    // best-effort refresh
                }
                try {
                    mediaRepository.syncImageCache()
                } catch (_: Exception) {
                    // best-effort cache rebuild
                }
            }

            dataStore.updateLastAppVersion(currentVersion)
        }

        private val defaultPreferences = AppPreferencesState.defaults()

        private val appPreferences: StateFlow<AppPreferencesState> =
            settingsRepository
                .observeAppPreferences()
                .stateInViewModel(viewModelScope, defaultPreferences)

        val dateFormat: StateFlow<String> =
            appPreferences
                .map { it.dateFormat }
                .stateInViewModel(viewModelScope, defaultPreferences.dateFormat)

        val timeFormat: StateFlow<String> =
            appPreferences
                .map { it.timeFormat }
                .stateInViewModel(viewModelScope, defaultPreferences.timeFormat)

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

        val hapticFeedbackEnabled: StateFlow<Boolean> =
            appPreferences
                .map { it.hapticFeedbackEnabled }
                .stateInViewModel(viewModelScope, defaultPreferences.hapticFeedbackEnabled)

        val showInputHints: StateFlow<Boolean> =
            appPreferences
                .map { it.showInputHints }
                .stateInViewModel(viewModelScope, defaultPreferences.showInputHints)

        val doubleTapEditEnabled: StateFlow<Boolean> =
            appPreferences
                .map { it.doubleTapEditEnabled }
                .stateInViewModel(viewModelScope, defaultPreferences.doubleTapEditEnabled)

        val shareCardStyle: StateFlow<String> =
            appPreferences
                .map { it.shareCardStyle }
                .stateInViewModel(viewModelScope, defaultPreferences.shareCardStyle)

        val shareCardShowTime: StateFlow<Boolean> =
            appPreferences
                .map { it.shareCardShowTime }
                .stateInViewModel(viewModelScope, defaultPreferences.shareCardShowTime)

        val shareCardShowBrand: StateFlow<Boolean> =
            appPreferences
                .map { it.shareCardShowBrand }
                .stateInViewModel(viewModelScope, defaultPreferences.shareCardShowBrand)

        val themeMode: StateFlow<String> =
            appPreferences
                .map { it.themeMode }
                .stateInViewModel(viewModelScope, defaultPreferences.themeMode)

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
