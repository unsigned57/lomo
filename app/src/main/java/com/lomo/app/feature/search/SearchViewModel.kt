package com.lomo.app.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.feature.preferences.observeAppPreferences
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.SettingsRepository
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.UpdateMemoUseCase
import com.lomo.ui.util.stateInViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        private val repository: MemoRepository,
        private val mediaRepository: com.lomo.domain.repository.MediaRepository,
        private val settingsRepository: SettingsRepository,
        val mapper: MemoUiMapper,
        private val imageMapProvider: ImageMapProvider,
        private val deleteMemoUseCase: DeleteMemoUseCase,
        private val updateMemoUseCase: UpdateMemoUseCase,
    ) : ViewModel() {
        private val _searchQuery = MutableStateFlow("")
        val searchQuery: StateFlow<String> = _searchQuery

        val rootDirectory: StateFlow<String?> =
            repository
                .getRootDirectory()
                .stateInViewModel(viewModelScope, null)

        val imageDirectory: StateFlow<String?> =
            repository
                .getImageDirectory()
                .stateInViewModel(viewModelScope, null)

        val imageMap: StateFlow<Map<String, android.net.Uri>> = imageMapProvider.imageMap

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

        val doubleTapEditEnabled: StateFlow<Boolean> =
            appPreferences
                .map { it.doubleTapEditEnabled }
                .stateInViewModel(viewModelScope, defaultPreferences.doubleTapEditEnabled)

        val activeDayCount: StateFlow<Int> =
            repository
                .getActiveDayCount()
                .stateInViewModel(viewModelScope, 0)

        @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
        val searchResults: StateFlow<List<Memo>> =
            _searchQuery
                .debounce(300)
                .flatMapLatest { query ->
                    if (query.isBlank()) {
                        flowOf(emptyList())
                    } else {
                        repository.searchMemosList(query)
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        @OptIn(ExperimentalCoroutinesApi::class)
        val searchUiModels: StateFlow<List<com.lomo.app.feature.main.MemoUiModel>> =
            combine(searchResults, rootDirectory, imageDirectory, imageMap) { results, rootDir, imageDir, currentImageMap ->
                SearchMappingBundle(
                    memos = results,
                    rootDirectory = rootDir,
                    imageDirectory = imageDir,
                    imageMap = currentImageMap,
                )
            }.mapLatest { bundle ->
                mapper.mapToUiModels(
                    memos = bundle.memos,
                    rootPath = bundle.rootDirectory,
                    imagePath = bundle.imageDirectory,
                    imageMap = bundle.imageMap,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        private data class SearchMappingBundle(
            val memos: List<Memo>,
            val rootDirectory: String?,
            val imageDirectory: String?,
            val imageMap: Map<String, android.net.Uri>,
        )

        fun onSearchQueryChanged(query: String) {
            _searchQuery.value = query
        }

        fun deleteMemo(memo: Memo) {
            viewModelScope.launch {
                try {
                    deleteMemoUseCase(memo)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Keep Search UI resilient.
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
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Keep Search UI resilient.
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
                    mediaRepository.syncImageCache()
                    onResult(path)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    onError?.invoke()
                }
            }
        }
    }
