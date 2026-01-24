package com.lomo.app.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// SearchUiState removed - using PagingData direct flow

@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        private val repository: MemoRepository,
        private val mapper: com.lomo.app.feature.main.MemoUiMapper,
        private val imageMapProvider: com.lomo.domain.provider.ImageMapProvider,
    ) : ViewModel() {
        private val _searchQuery = MutableStateFlow("")
        val searchQuery: StateFlow<String> = _searchQuery

        val rootDirectory: StateFlow<String?> =
            repository
                .getRootDirectory()
                .stateInViewModel(viewModelScope, null) // Using extension

        val imageDirectory: StateFlow<String?> =
            repository
                .getImageDirectory()
                .stateInViewModel(viewModelScope, null)

        val dateFormat: StateFlow<String> =
            repository
                .getDateFormat()
                .stateInViewModel(viewModelScope, com.lomo.data.util.PreferenceKeys.Defaults.DATE_FORMAT)

        val timeFormat: StateFlow<String> =
            repository
                .getTimeFormat()
                .stateInViewModel(viewModelScope, com.lomo.data.util.PreferenceKeys.Defaults.TIME_FORMAT)

        // Image map loading removed - using shared ImageMapProvider

        @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
        val pagedResults: kotlinx.coroutines.flow.Flow<PagingData<com.lomo.app.feature.main.MemoUiModel>> =
            kotlinx.coroutines.flow
                .combine(
                    _searchQuery.debounce(300),
                    rootDirectory,
                    imageDirectory,
                    imageMapProvider.imageMap, // Use shared ImageMapProvider
                ) { query, root, imageRoot, imageMap ->
                    DataBundle(query, root, imageRoot, imageMap)
                }.flatMapLatest { bundle ->
                    if (bundle.query.isBlank()) {
                        kotlinx.coroutines.flow.flowOf(PagingData.empty<MemoUiModel>())
                    } else {
                        repository.searchMemos(bundle.query).map { pagingData ->
                            pagingData.map<Memo, MemoUiModel> { memo ->
                                mapper.mapToUiModel(memo, bundle.root, bundle.imageRoot, bundle.imageMap)
                            }
                        }
                    }
                }.cachedIn(viewModelScope)

        private data class DataBundle(
            val query: String,
            val root: String?,
            val imageRoot: String?,
            val imageMap: Map<String, android.net.Uri>,
        )

        fun onSearchQueryChanged(query: String) {
            _searchQuery.value = query
        }

        fun deleteMemo(memo: Memo) {
            viewModelScope.launch {
                repository.deleteMemo(memo)
            }
        }
    }
