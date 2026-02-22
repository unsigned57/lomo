package com.lomo.app.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.SettingsRepository
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.UpdateMemoUseCase
import com.lomo.ui.util.OptimisticMutationManager
import com.lomo.ui.util.stateInViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

// SearchUiState removed - using PagingData direct flow

@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        private val repository: MemoRepository,
        private val mediaRepository: com.lomo.domain.repository.MediaRepository,
        private val settingsRepository: SettingsRepository,
        private val mapper: com.lomo.app.feature.main.MemoUiMapper,
        private val imageMapProvider: com.lomo.domain.provider.ImageMapProvider,
        private val deleteMemoUseCase: DeleteMemoUseCase,
        private val updateMemoUseCase: UpdateMemoUseCase,
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

        val dateFormat: StateFlow<String> =
            settingsRepository
                .getDateFormat()
                .stateInViewModel(viewModelScope, com.lomo.data.util.PreferenceKeys.Defaults.DATE_FORMAT)

        val timeFormat: StateFlow<String> =
            settingsRepository
                .getTimeFormat()
                .stateInViewModel(viewModelScope, com.lomo.data.util.PreferenceKeys.Defaults.TIME_FORMAT)

        val shareCardStyle: StateFlow<String> =
            settingsRepository
                .getShareCardStyle()
                .stateInViewModel(viewModelScope, com.lomo.data.util.PreferenceKeys.Defaults.SHARE_CARD_STYLE)

        val shareCardShowTime: StateFlow<Boolean> =
            settingsRepository
                .isShareCardShowTimeEnabled()
                .stateInViewModel(viewModelScope, com.lomo.data.util.PreferenceKeys.Defaults.SHARE_CARD_SHOW_TIME)

        val shareCardShowBrand: StateFlow<Boolean> =
            settingsRepository
                .isShareCardShowBrandEnabled()
                .stateInViewModel(viewModelScope, com.lomo.data.util.PreferenceKeys.Defaults.SHARE_CARD_SHOW_BRAND)

        val doubleTapEditEnabled: StateFlow<Boolean> =
            settingsRepository
                .isDoubleTapEditEnabled()
                .stateInViewModel(viewModelScope, com.lomo.data.util.PreferenceKeys.Defaults.DOUBLE_TAP_EDIT_ENABLED)

        val activeDayCount: StateFlow<Int> =
            repository
                .getActiveDayCount()
                .stateInViewModel(viewModelScope, 0)

        // Optimistic UI: shared manager handles the two-phase fade-then-collapse pattern
        private val mutations = OptimisticMutationManager(viewModelScope)
        val pendingMutations = mutations.state

        @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
        val pagedResults: kotlinx.coroutines.flow.Flow<PagingData<com.lomo.app.feature.main.MemoUiModel>> =
            kotlinx.coroutines.flow
                .combine(
                    _searchQuery.debounce(300),
                    rootDirectory,
                    imageDirectory,
                    imageMapProvider.imageMap,
                    mutations.state,
                ) { query, root, imageRoot, imageMap, pendingMutations ->
                    DataBundle(query, root, imageRoot, imageMap, pendingMutations)
                }.flatMapLatest { bundle ->
                    if (bundle.query.isBlank()) {
                        kotlinx.coroutines.flow.flowOf(PagingData.empty<MemoUiModel>())
                    } else {
                        repository.searchMemos(bundle.query).map { pagingData ->
                            pagingData
                                .map { memo ->
                                    mapper.mapToUiModel(
                                        memo = memo,
                                        rootPath = bundle.root,
                                        imagePath = bundle.imageRoot,
                                        imageMap = bundle.imageMap,
                                        isDeleting =
                                            bundle.mutations[memo.id]?.isHidden == false &&
                                                bundle.mutations[memo.id] != null,
                                    )
                                }.filter { memo ->
                                    val mutation = bundle.mutations[memo.memo.id]
                                    mutation?.isHidden != true
                                }
                        }
                    }
                }.catch { e ->
                    e.printStackTrace()
                    emit(PagingData.empty<MemoUiModel>())
                }.cachedIn(viewModelScope)

        private data class DataBundle(
            val query: String,
            val root: String?,
            val imageRoot: String?,
            val imageMap: Map<String, android.net.Uri>,
            val mutations: Map<String, OptimisticMutationManager.MutationState>,
        )

        fun onSearchQueryChanged(query: String) {
            _searchQuery.value = query
        }

        fun deleteMemo(memo: Memo) {
            mutations.delete(
                id = memo.id,
                onError = { /* silently ignore; item will reappear */ },
            ) {
                deleteMemoUseCase(memo)
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
                } catch (_: Exception) {
                    // Keep Search UI resilient; paging stream will recover on next invalidation.
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
                } catch (_: Exception) {
                    // Keep Search UI resilient; skip insertion on failure.
                    onError?.invoke()
                }
            }
        }
    }
