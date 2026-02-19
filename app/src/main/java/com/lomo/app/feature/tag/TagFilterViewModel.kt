package com.lomo.app.feature.tag

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TagFilterViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val memoRepository: MemoRepository,
        private val settingsRepository: SettingsRepository,
        val mapper: com.lomo.app.feature.main.MemoUiMapper,
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
            settingsRepository
                .getDateFormat()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.lomo.data.util.PreferenceKeys.Defaults.DATE_FORMAT)

        val timeFormat: StateFlow<String> =
            settingsRepository
                .getTimeFormat()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.lomo.data.util.PreferenceKeys.Defaults.TIME_FORMAT)

        // Optimistic UI: Pending mutations
        private val _pendingMutations = MutableStateFlow<Map<String, Mutation>>(emptyMap())
        val pendingMutations: StateFlow<Map<String, Mutation>> = _pendingMutations

        sealed interface Mutation {
            data class Delete(
                val isHidden: Boolean = false,
            ) : Mutation
        }

        // Image map loading removed - using shared ImageMapProvider

        val rootDir: StateFlow<String?> = rootDirectory
        val imageDir: StateFlow<String?> = imageDirectory
        val imageMap: StateFlow<Map<String, android.net.Uri>> = imageMapProvider.imageMap

        val pagedMemos: Flow<PagingData<Memo>> =
            memoRepository
                .getMemosByTag(tag = tagName)
                .cachedIn(viewModelScope)

        fun deleteMemo(memo: Memo) {
            // 1. Optimistic Delete (Visible Phase)
            _pendingMutations.update { it + (memo.id to Mutation.Delete(isHidden = false)) }

            viewModelScope.launch {
                try {
                    // 2. Wait for UI animations (300ms)
                    delay(300)

                    // 3. Optimistic Filter (Collapse Item)
                    _pendingMutations.update { it + (memo.id to Mutation.Delete(isHidden = true)) }

                    memoRepository.deleteMemo(memo)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    _pendingMutations.update { it - memo.id }
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("TagFilterViewModel", "Failed to delete memo", e)
                    _pendingMutations.update { it - memo.id }
                } finally {
                    delay(3000)
                    _pendingMutations.update { it - memo.id }
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
