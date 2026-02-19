package com.lomo.app.feature.main

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import com.lomo.app.BuildConfig
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.SettingsRepository
import com.lomo.domain.repository.WidgetRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
        private val imageMapProvider: com.lomo.domain.provider.ImageMapProvider,
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

        fun checkForUpdates() {
            viewModelScope.launch {
                try {
                    // First check if enabled
                    val enabled = settingsRepository.isCheckUpdatesOnStartupEnabled().first()
                    if (enabled) {
                        val url = updateManager.checkForUpdates()
                        if (url != null) {
                            _updateUrl.value = url
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
        }

        // ... existing code ...

        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage

        // Track in-flight TODO update jobs to handle race conditions: (MemoId, LineIndex) -> Job
        private val pendingTodoJobs = mutableMapOf<Pair<String, Int>, kotlinx.coroutines.Job>()

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

        // TODO override map: MemoId -> (LineIndex -> isChecked)
        // This holds pending checkbox states that survive Paging refreshes
        private val _todoOverrides = MutableStateFlow<Map<String, Map<Int, Boolean>>>(emptyMap())
        val todoOverrides: StateFlow<Map<String, Map<Int, Boolean>>> = _todoOverrides

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

        // Optimistic UI: Pending mutations
        private val _pendingMutations = MutableStateFlow<Map<String, MemoMutation>>(emptyMap())
        val pendingMutations: StateFlow<Map<String, MemoMutation>> = _pendingMutations

        sealed interface MemoMutation {
            data class Update(
                val newContent: String,
                val timestamp: Long,
            ) : MemoMutation

            data class Delete(
                val timestamp: Long,
                val isHidden: Boolean = false,
            ) : MemoMutation

            // Creation is harder with Paging, might need separate list or RemoteMediator trick.
            // For now, let's focus on Update/Delete responsiveness.
            data class Create(
                val content: String,
                val timestamp: Long,
                val tempId: String,
            ) : MemoMutation
        }

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        private val rawMemosFlow =
            combine(_searchQuery, _selectedTag) { query: String, tag: String? -> query to tag }
                .flatMapLatest { (query, tag) -> getFilteredMemosUseCase(query, tag) }
                .cachedIn(viewModelScope)

        val pagedMemos: kotlinx.coroutines.flow.Flow<androidx.paging.PagingData<Memo>> =
            rawMemosFlow.cachedIn(viewModelScope)

        private data class DataBundle(
            val query: String,
            val tag: String?,
            val rootDir: String?,
            val imageDir: String?,
            val imageMap: Map<String, android.net.Uri>,
            val mutations: Map<String, MemoMutation>,
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
                _errorMessage.value = "Cannot save empty memo"
                return
            }
            if (content.length > com.lomo.domain.AppConfig.MAX_MEMO_LENGTH) {
                _errorMessage.value =
                    "Content exceeds limit of ${com.lomo.domain.AppConfig.MAX_MEMO_LENGTH} characters"
                return
            }
            viewModelScope.launch {
                try {
                    // Optimistic: Create temp ID and add to pending (TODO: Paging Header Injection)
                    // For Add, since we can't easily inject into PagingData.from(flow),
                    // we rely on the fact that refresh() is fast or we manually refresh.
                    // But WAIT, we can just let it be slow for now, or use a separate "Creating..." list header.

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
            val timestamp = System.currentTimeMillis()
            // 1. Optimistic Delete (Fade Out)
            _pendingMutations.update { it + (memo.id to MemoMutation.Delete(timestamp, isHidden = false)) }

            viewModelScope.launch {
                try {
                    // 2. Wait for UI animations (Fade 300ms)
                    delay(300)

                    // 3. Optimistic Filter (Collapse Item)
                    // This forces the item to be removed from PagingData immediately
                    _pendingMutations.update { it + (memo.id to MemoMutation.Delete(timestamp, isHidden = true)) }

                    deleteMemoUseCase(memo)
                    // Update widget after deleting memo
                    widgetRepository.updateAllWidgets()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    _pendingMutations.update { it - memo.id }
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.message
                    _pendingMutations.update { it - memo.id }
                } finally {
                    // Keep the mutation mask for 3s to ensure Paging stream reflects the deletion
                    delay(3000)
                    _pendingMutations.update { it - memo.id }
                }
            }
        }

        fun updateMemo(
            memo: Memo,
            newContent: String,
        ) {
            val timestamp = System.currentTimeMillis()
            // Optimistic Update
            _pendingMutations.update { it + (memo.id to MemoMutation.Update(newContent, timestamp)) }

            viewModelScope.launch {
                try {
                    updateMemoUseCase(memo, newContent)
                    // Update widget after updating memo
                    widgetRepository.updateAllWidgets()

                    // Bug 3: Memo saved, keep the images
                    ephemeralImageFilenames.clear()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    _pendingMutations.update { it - memo.id }
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.message
                    _pendingMutations.update { it - memo.id }
                } finally {
                    delay(5000)
                    _pendingMutations.update { it - memo.id }
                }
            }
        }

        fun updateMemo(
            memo: Memo,
            lineIndex: Int,
            checked: Boolean,
        ) {
            val key = memo.id to lineIndex

            // 1. Immediately update the overlay (optimistic UI)
            _todoOverrides.update { current ->
                val memoMap = current[memo.id]?.toMutableMap() ?: mutableMapOf()
                memoMap[lineIndex] = checked
                current + (memo.id to memoMap)
            }

            // 2. Cancel any pending update for this specific item to avoid race conditions
            pendingTodoJobs[key]?.cancel()

            // 3. Trigger DB update in background
            val job =
                viewModelScope.launch {
                    try {
                        val newContent = textProcessor.toggleCheckbox(memo.content, lineIndex, checked)
                        updateMemoUseCase(memo, newContent)
                        // Widget will be updated by repository or we can trigger it here if needed
                        // WidgetUpdater.updateAllWidgets(appContext)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _errorMessage.value = "Failed to update todo: ${e.message}"
                    } finally {
                        // 4. After successful write (or error), clear the override ONLY if this is still the active job
                        // This prevents older out-of-order writes from clearing a newer optimistic state
                        if (pendingTodoJobs[key] == coroutineContext[kotlinx.coroutines.Job]) {
                            pendingTodoJobs.remove(key)
                            _todoOverrides.update { current ->
                                val memoMap = current[memo.id]?.toMutableMap() ?: return@update current
                                memoMap.remove(lineIndex)
                                if (memoMap.isEmpty()) current - memo.id else current + (memo.id to memoMap)
                            }
                        }
                    }
                }
            pendingTodoJobs[key] = job
        }

        fun saveImage(
            uri: android.net.Uri,
            onResult: (String) -> Unit,
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

        val dateFormat: StateFlow<String> =
            settingsRepository
                .getDateFormat()
                .stateIn(
                    scope = viewModelScope,
                    started =
                        kotlinx.coroutines.flow.SharingStarted
                            .WhileSubscribed(5000),
                    initialValue = com.lomo.data.util.PreferenceKeys.Defaults.DATE_FORMAT,
                )

        val timeFormat: StateFlow<String> =
            settingsRepository
                .getTimeFormat()
                .stateIn(
                    scope = viewModelScope,
                    started =
                        kotlinx.coroutines.flow.SharingStarted
                            .WhileSubscribed(5000),
                    initialValue = com.lomo.data.util.PreferenceKeys.Defaults.TIME_FORMAT,
                )

        val hapticFeedbackEnabled: StateFlow<Boolean> =
            settingsRepository
                .isHapticFeedbackEnabled()
                .stateIn(
                    scope = viewModelScope,
                    started =
                        kotlinx.coroutines.flow.SharingStarted
                            .WhileSubscribed(5000),
                    initialValue =
                        com.lomo.data.util.PreferenceKeys.Defaults
                            .HAPTIC_FEEDBACK_ENABLED,
                )

        val showInputHints: StateFlow<Boolean> =
            settingsRepository
                .isShowInputHintsEnabled()
                .stateIn(
                    scope = viewModelScope,
                    started =
                        kotlinx.coroutines.flow.SharingStarted
                            .WhileSubscribed(5000),
                    initialValue = com.lomo.data.util.PreferenceKeys.Defaults.SHOW_INPUT_HINTS,
                )

        val themeMode: StateFlow<String> =
            settingsRepository
                .getThemeMode()
                .stateIn(
                    scope = viewModelScope,
                    started =
                        kotlinx.coroutines.flow.SharingStarted
                            .WhileSubscribed(5000),
                    initialValue = com.lomo.data.util.PreferenceKeys.Defaults.THEME_MODE,
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

// MainUiState removed
