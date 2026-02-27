package com.lomo.app.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.memo.MemoActionDelegate
import com.lomo.app.feature.memo.MemoFlowProcessor
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.feature.preferences.activeDayCountState
import com.lomo.app.feature.preferences.appPreferencesState
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.PreferencesRepository
import com.lomo.ui.util.stateInViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        private val repository: MemoRepository,
        private val directorySettings: DirectorySettingsRepository,
        private val preferencesRepository: PreferencesRepository,
        private val imageMapProvider: ImageMapProvider,
        private val memoFlowProcessor: MemoFlowProcessor,
        private val memoActionDelegate: MemoActionDelegate,
    ) : ViewModel() {
        private val _searchQuery = MutableStateFlow("")
        val searchQuery: StateFlow<String> = _searchQuery

        val rootDirectory: StateFlow<String?> =
            directorySettings
                .getRootDirectory()
                .stateInViewModel(viewModelScope, null)

        val imageDirectory: StateFlow<String?> =
            directorySettings
                .getImageDirectory()
                .stateInViewModel(viewModelScope, null)

        val imageMap: StateFlow<Map<String, android.net.Uri>> = imageMapProvider.imageMap

        val appPreferences: StateFlow<AppPreferencesState> =
            preferencesRepository.appPreferencesState(viewModelScope)

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
            memoFlowProcessor
                .mapMemoFlow(
                    memos = searchResults,
                    rootDirectory = rootDirectory,
                    imageDirectory = imageDirectory,
                    imageMap = imageMap,
                ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        fun onSearchQueryChanged(query: String) {
            _searchQuery.value = query
        }

        fun deleteMemo(memo: Memo) {
            viewModelScope.launch {
                memoActionDelegate
                    .deleteMemo(memo)
                    .onFailure {
                        // Keep Search UI resilient.
                    }
            }
        }

        fun updateMemo(
            memo: Memo,
            newContent: String,
        ) {
            viewModelScope.launch {
                memoActionDelegate
                    .updateMemo(memo, newContent)
                    .onFailure {
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
                memoActionDelegate
                    .saveImage(uri)
                    .onSuccess(onResult)
                    .onFailure {
                        onError?.invoke()
                    }
            }
        }
    }
