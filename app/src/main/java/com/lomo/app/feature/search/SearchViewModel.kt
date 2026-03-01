package com.lomo.app.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.toUserMessage
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.feature.preferences.activeDayCountState
import com.lomo.app.feature.preferences.appPreferencesState
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageArea
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import com.lomo.ui.util.stateInViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
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
        private val appConfigRepository: AppConfigRepository,
        private val imageMapProvider: ImageMapProvider,
        private val memoUiMapper: MemoUiMapper,
        private val deleteMemoUseCase: DeleteMemoUseCase,
        private val updateMemoContentUseCase: UpdateMemoContentUseCase,
        private val saveImageUseCase: SaveImageUseCase,
    ) : ViewModel() {
        private val _searchQuery = MutableStateFlow("")
        val searchQuery: StateFlow<String> = _searchQuery
        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

        val rootDirectory: StateFlow<String?> =
            appConfigRepository
                .observeLocation(StorageArea.ROOT)
                .map { it?.raw }
                .stateInViewModel(viewModelScope, null)

        val imageDirectory: StateFlow<String?> =
            appConfigRepository
                .observeLocation(StorageArea.IMAGE)
                .map { it?.raw }
                .stateInViewModel(viewModelScope, null)

        val imageMap: StateFlow<Map<String, android.net.Uri>> = imageMapProvider.imageMap

        val appPreferences: StateFlow<AppPreferencesState> =
            appConfigRepository.appPreferencesState(viewModelScope)

        val activeDayCount: StateFlow<Int> =
            repository.activeDayCountState(viewModelScope)

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
                }.catch {
                    emit(emptyList())
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        @OptIn(ExperimentalCoroutinesApi::class)
        val searchUiModels: StateFlow<List<com.lomo.app.feature.main.MemoUiModel>> =
            combine(searchResults, rootDirectory, imageDirectory, imageMap) {
                    memos,
                    rootDir,
                    imageDir,
                    currentImageMap,
                ->
                UiMemoMappingInput(
                    memos = memos,
                    rootDirectory = rootDir,
                    imageDirectory = imageDir,
                    imageMap = currentImageMap,
                )
            }.distinctUntilChanged()
                .mapLatest { input ->
                    memoUiMapper.mapToUiModels(
                        memos = input.memos,
                        rootPath = input.rootDirectory,
                        imagePath = input.imageDirectory,
                        imageMap = input.imageMap,
                    )
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        fun onSearchQueryChanged(query: String) {
            _searchQuery.value = query
        }

        fun deleteMemo(memo: Memo) {
            viewModelScope.launch {
                try {
                    deleteMemoUseCase(memo)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.toUserMessage("Failed to delete memo")
                }
            }
        }

        fun updateMemo(
            memo: Memo,
            newContent: String,
        ) {
            viewModelScope.launch {
                try {
                    updateMemoContentUseCase(memo, newContent)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.toUserMessage("Failed to update memo")
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
                    onResult(saveImageUseCase(uri.toString()))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.toUserMessage("Failed to save image")
                    onError?.invoke()
                }
            }
        }

        fun clearError() {
            _errorMessage.value = null
        }

        private data class UiMemoMappingInput(
            val memos: List<Memo>,
            val rootDirectory: String?,
            val imageDirectory: String?,
            val imageMap: Map<String, android.net.Uri>,
        )
    }
