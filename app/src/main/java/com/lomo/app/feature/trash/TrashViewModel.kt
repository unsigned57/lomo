package com.lomo.app.feature.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.SettingsRepository
import com.lomo.ui.util.OptimisticMutationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class TrashViewModel
    @Inject
    constructor(
        private val repository: MemoRepository,
        private val settingsRepository: SettingsRepository,
        private val imageMapProvider: com.lomo.domain.provider.ImageMapProvider,
        val mapper: com.lomo.app.feature.main.MemoUiMapper,
    ) : ViewModel() {
        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage

        // Optimistic UI: shared manager handles the two-phase fade-then-collapse pattern
        private val mutations = OptimisticMutationManager(viewModelScope)
        val pendingMutations = mutations.state

        // Image map provided by shared ImageMapProvider
        val imageMap: StateFlow<Map<String, android.net.Uri>> = imageMapProvider.imageMap
        val imageDirectory: StateFlow<String?> =
            repository.getImageDirectory().stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                null,
            )

        // Filter out optimistically deleted items for smooth animation and map to UiModel
        @OptIn(ExperimentalCoroutinesApi::class)
        val pagedTrash: Flow<PagingData<Memo>> =
            repository
                .getDeletedMemos()
                .cachedIn(viewModelScope)

        private data class DataBundle(
            val rootDir: String?,
            val imageDir: String?,
            val imageMap: Map<String, android.net.Uri>,
        )

        val dateFormat: StateFlow<String> =
            settingsRepository
                .getDateFormat()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.lomo.data.util.PreferenceKeys.Defaults.DATE_FORMAT)

        val timeFormat: StateFlow<String> =
            settingsRepository
                .getTimeFormat()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.lomo.data.util.PreferenceKeys.Defaults.TIME_FORMAT)

        val rootDirectory: StateFlow<String?> =
            repository
                .getRootDirectory()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        fun restoreMemo(memo: Memo) {
            mutations.delete(
                id = memo.id,
                onError = { e ->
                    _errorMessage.value = "Failed to restore memo: ${e.message}"
                },
            ) {
                repository.restoreMemo(memo)
            }
        }

        fun deletePermanently(memo: Memo) {
            mutations.delete(
                id = memo.id,
                onError = { e ->
                    _errorMessage.value = "Failed to delete memo: ${e.message}"
                },
            ) {
                repository.deletePermanently(memo)
            }
        }

        fun clearError() {
            _errorMessage.value = null
        }
    }
