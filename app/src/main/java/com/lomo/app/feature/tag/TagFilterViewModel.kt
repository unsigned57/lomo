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
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.UpdateMemoUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
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
        private val mediaRepository: com.lomo.domain.repository.MediaRepository,
        private val settingsRepository: SettingsRepository,
        val mapper: com.lomo.app.feature.main.MemoUiMapper,
        private val imageMapProvider: com.lomo.domain.provider.ImageMapProvider,
        private val deleteMemoUseCase: DeleteMemoUseCase,
        private val updateMemoUseCase: UpdateMemoUseCase,
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

        val shareCardStyle: StateFlow<String> =
            settingsRepository
                .getShareCardStyle()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.lomo.data.util.PreferenceKeys.Defaults.SHARE_CARD_STYLE)

        val shareCardShowTime: StateFlow<Boolean> =
            settingsRepository
                .isShareCardShowTimeEnabled()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    com.lomo.data.util.PreferenceKeys.Defaults.SHARE_CARD_SHOW_TIME,
                )

        val shareCardShowBrand: StateFlow<Boolean> =
            settingsRepository
                .isShareCardShowBrandEnabled()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    com.lomo.data.util.PreferenceKeys.Defaults.SHARE_CARD_SHOW_BRAND,
                )

        val doubleTapEditEnabled: StateFlow<Boolean> =
            settingsRepository
                .isDoubleTapEditEnabled()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    com.lomo.data.util.PreferenceKeys.Defaults.DOUBLE_TAP_EDIT_ENABLED,
                )

        val activeDayCount: StateFlow<Int> =
            memoRepository
                .getActiveDayCount()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

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

                    deleteMemoUseCase(memo)
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
                    updateMemoUseCase(memo, newContent)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("TagFilterViewModel", "Failed to update memo", e)
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
                } catch (e: Exception) {
                    android.util.Log.e("TagFilterViewModel", "Failed to save image", e)
                    onError?.invoke()
                }
            }
        }
    }
