package com.lomo.app.feature.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TrashViewModel
    @Inject
    constructor(
        private val repository: MemoRepository,
    ) : ViewModel() {
        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage

        // Track optimistically deleted/restored items
        private val _deletedIds = MutableStateFlow<Set<String>>(emptySet())

        // Filter out optimistically deleted items for smooth animation
        @OptIn(ExperimentalCoroutinesApi::class)
        val pagedTrash: Flow<PagingData<Memo>> =
            _deletedIds
                .flatMapLatest { deletedIds ->
                    repository.getDeletedMemos().map { pagingData ->
                        pagingData.filter { memo -> memo.id !in deletedIds }
                    }
                }.cachedIn(viewModelScope)

        val dateFormat: StateFlow<String> =
            repository
                .getDateFormat()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.lomo.data.util.PreferenceKeys.Defaults.DATE_FORMAT)

        val timeFormat: StateFlow<String> =
            repository
                .getTimeFormat()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.lomo.data.util.PreferenceKeys.Defaults.TIME_FORMAT)

        val rootDirectory: StateFlow<String?> =
            repository
                .getRootDirectory()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        fun restoreMemo(memo: Memo) {
            // Optimistic: remove from list immediately
            _deletedIds.value = _deletedIds.value + memo.id

            viewModelScope.launch {
                try {
                    repository.restoreMemo(memo)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Rollback on error
                    _deletedIds.value = _deletedIds.value - memo.id
                    _errorMessage.value = "Failed to restore memo: ${e.message}"
                }
            }
        }

        fun deletePermanently(memo: Memo) {
            // Optimistic: remove from list immediately
            _deletedIds.value = _deletedIds.value + memo.id

            viewModelScope.launch {
                try {
                    repository.deletePermanently(memo)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Rollback on error
                    _deletedIds.value = _deletedIds.value - memo.id
                    _errorMessage.value = "Failed to delete memo: ${e.message}"
                }
            }
        }

        fun clearError() {
            _errorMessage.value = null
        }
    }
