package com.lomo.app.feature.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.memo.MemoFlowProcessor
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.feature.preferences.observeAppPreferences
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.SettingsRepository
import com.lomo.ui.util.stateInViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TrashViewModel
    @Inject
    constructor(
        private val repository: MemoRepository,
        private val settingsRepository: SettingsRepository,
        private val imageMapProvider: ImageMapProvider,
        private val memoFlowProcessor: MemoFlowProcessor,
    ) : ViewModel() {
        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage

        private val deletingMemoIds = MutableStateFlow<Set<String>>(emptySet())

        val imageMap: StateFlow<Map<String, android.net.Uri>> = imageMapProvider.imageMap
        val imageDirectory: StateFlow<String?> =
            repository.getImageDirectory().stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                null,
            )

        val trashMemos: StateFlow<List<Memo>> =
            repository
                .getDeletedMemosList()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        init {
            // Keep deletion flags only for rows that still exist in current trash list.
            // This avoids alpha "bounce back" when DB removal arrives slightly later.
            trashMemos
                .onEach { memos ->
                    val existingIds = memos.asSequence().map { it.id }.toSet()
                    deletingMemoIds.update { current -> current.intersect(existingIds) }
                }.launchIn(viewModelScope)
        }

        private val defaultPreferences = AppPreferencesState.defaults()

        val appPreferences: StateFlow<AppPreferencesState> =
            settingsRepository
                .observeAppPreferences()
                .stateInViewModel(viewModelScope, defaultPreferences)

        val rootDirectory: StateFlow<String?> =
            repository
                .getRootDirectory()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val trashUiMemos: StateFlow<List<com.lomo.app.feature.main.MemoUiModel>> =
            combine(
                memoFlowProcessor.mapMemoFlow(
                    memos = trashMemos,
                    rootDirectory = rootDirectory,
                    imageDirectory = imageDirectory,
                    imageMap = imageMap,
                ),
                deletingMemoIds,
            ) { uiModels, deletingIds ->
                uiModels.map { uiModel ->
                    uiModel.copy(isDeleting = deletingIds.contains(uiModel.memo.id))
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        fun restoreMemo(memo: Memo) {
            viewModelScope.launch {
                deletingMemoIds.update { it + memo.id }
                kotlinx.coroutines.delay(300L)
                var restored = false
                try {
                    repository.restoreMemo(memo)
                    restored = true
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = "Failed to restore memo: ${e.message}"
                } finally {
                    if (!restored) {
                        deletingMemoIds.update { it - memo.id }
                    }
                }
            }
        }

        fun deletePermanently(memo: Memo) {
            viewModelScope.launch {
                deletingMemoIds.update { it + memo.id }
                kotlinx.coroutines.delay(300L)
                var deleted = false
                try {
                    repository.deletePermanently(memo)
                    deleted = true
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = "Failed to delete memo: ${e.message}"
                } finally {
                    if (!deleted) {
                        deletingMemoIds.update { it - memo.id }
                    }
                }
            }
        }

        fun clearError() {
            _errorMessage.value = null
        }
    }
