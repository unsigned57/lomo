package com.lomo.app.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.memo.MemoActionDelegate
import com.lomo.app.feature.memo.MemoFlowProcessor
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.feature.preferences.observeAppPreferences
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.SettingsRepository
import com.lomo.ui.util.stateInViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
        private val settingsRepository: SettingsRepository,
        private val imageMapProvider: ImageMapProvider,
        private val memoFlowProcessor: MemoFlowProcessor,
        private val memoActionDelegate: MemoActionDelegate,
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

        val appPreferences: StateFlow<AppPreferencesState> =
            settingsRepository
                .observeAppPreferences()
                .stateInViewModel(viewModelScope, defaultPreferences)

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
