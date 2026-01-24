package com.lomo.app.feature.tag

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TagFilterViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val memoRepository: MemoRepository,
        private val mapper: com.lomo.app.feature.main.MemoUiMapper,
        private val imageMapProvider: com.lomo.domain.provider.ImageMapProvider,
    ) : ViewModel() {
        val tagName: String = savedStateHandle.get<String>("tagName") ?: ""

        private val rootDirectory: StateFlow<String?> =
            memoRepository
                .getRootDirectory()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        private val imageDirectory: StateFlow<String?> =
            memoRepository
                .getImageDirectory()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        val dateFormat: StateFlow<String> =
            memoRepository
                .getDateFormat()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.lomo.data.util.PreferenceKeys.Defaults.DATE_FORMAT)

        val timeFormat: StateFlow<String> =
            memoRepository
                .getTimeFormat()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.lomo.data.util.PreferenceKeys.Defaults.TIME_FORMAT)

        // Image map loading removed - using shared ImageMapProvider

        val pagedMemos: Flow<PagingData<com.lomo.app.feature.main.MemoUiModel>> =
            kotlinx.coroutines.flow
                .combine(
                    memoRepository.getMemosByTag(tag = tagName).cachedIn(viewModelScope),
                    rootDirectory,
                    imageDirectory,
                    imageMapProvider.imageMap, // Use shared ImageMapProvider
                ) { pagingData: PagingData<Memo>, root: String?, imageRoot: String?, imageMap: Map<String, android.net.Uri> ->
                    pagingData.map { memo ->
                        mapper.mapToUiModel(memo, root, imageRoot, imageMap)
                    }
                }.cachedIn(viewModelScope)

        fun deleteMemo(memo: Memo) {
            viewModelScope.launch {
                try {
                    memoRepository.deleteMemo(memo)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("TagFilterViewModel", "Failed to delete memo", e)
                }
            }
        }

        fun updateMemo(
            memo: Memo,
            newContent: String,
        ) {
            viewModelScope.launch {
                try {
                    memoRepository.updateMemo(memo, newContent)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("TagFilterViewModel", "Failed to update memo", e)
                }
            }
        }
    }
