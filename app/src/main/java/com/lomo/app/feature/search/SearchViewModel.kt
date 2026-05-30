package com.lomo.app.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.AppConfigStateProvider
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoActionOrderScopes
import com.lomo.app.feature.common.MemoCollectionCapabilities
import com.lomo.app.feature.common.MemoCollectionProjectionMapper
import com.lomo.app.feature.common.MemoCollectionStateHolder
import com.lomo.app.feature.common.MemoCollectionUiState
import com.lomo.app.feature.common.MemoCollectionWindowStateHolder
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.app.feature.memo.MemoActionId
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoSortOption
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.ObserveActiveDayCountUseCase
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.SearchMemosPageUseCase
import com.lomo.domain.usecase.ToggleMemoCheckboxUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

private const val SEARCH_QUERY_DEBOUNCE_MILLIS = 300L
private const val SEARCH_LOADING_SHOW_DELAY_MILLIS = 120L
private const val SEARCH_LOADING_MIN_VISIBLE_MILLIS = 280L

@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        private val observeActiveDayCountUseCase: ObserveActiveDayCountUseCase,
        private val appConfigStateProvider: AppConfigStateProvider,
        private val appConfigUiCoordinator: AppConfigUiCoordinator,
        private val imageMapProvider: ImageMapProvider,
        private val projectionMapper: MemoCollectionProjectionMapper,
        private val searchMemosPageUseCase: SearchMemosPageUseCase,
        private val deleteMemoUseCase: DeleteMemoUseCase,
        private val updateMemoContentUseCase: UpdateMemoContentUseCase,
        private val saveImageUseCase: SaveImageUseCase,
        private val toggleMemoCheckboxUseCase: ToggleMemoCheckboxUseCase,
    ) : ViewModel() {
        private val _searchQuery = MutableStateFlow("")
        val searchQuery: StateFlow<String> = _searchQuery
        val searchFilterController = com.lomo.app.feature.common.MemoListFilterController()
        val searchFilter: StateFlow<MemoListFilter> = searchFilterController.filter
        private var reportSearchLoadError: (Throwable) -> Unit = {}

        val activeDayCount: StateFlow<Int> =
            observeActiveDayCountUseCase()
                .stateIn(viewModelScope, appWhileSubscribed(), 0)

        @OptIn(FlowPreview::class)
        private val searchQueryInput =
            combine(_searchQuery, searchFilterController.filter) { rawQuery, filter ->
                SearchQueryInput(query = rawQuery.trim(), filter = filter)
            }.debounce(SEARCH_QUERY_DEBOUNCE_MILLIS)

        private val collectionWindowStateHolder =
            MemoCollectionWindowStateHolder(
                sourceInput = searchQueryInput,
                source = { input ->
                    searchMemosPageUseCase.getPagingSource(
                        query = input.query,
                        filter = input.filter,
                    )
                },
                scope = viewModelScope,
                onLoadError = { throwable ->
                    reportSearchLoadError(throwable)
                },
            )

        val isSearching: StateFlow<Boolean> =
            collectionWindowStateHolder.isLoading

        val showLoading: StateFlow<Boolean> =
            collectionWindowStateHolder.isLoading
                .toDelayedSearchLoading()
                .stateIn(viewModelScope, appWhileSubscribed(), false)

        private val collectionStateHolder =
            MemoCollectionStateHolder(
                source = collectionWindowStateHolder.memos,
                configStateProvider = appConfigStateProvider,
                imageMapProvider = imageMapProvider,
                memoUiMapper = projectionMapper.memoUiMapper,
                capabilities =
                    MemoCollectionCapabilities.Editable(
                        deleteMemo = deleteMemoUseCase::invoke,
                        updateMemo = updateMemoContentUseCase::invoke,
                        toggleTodo = { memo, lineIndex, checked ->
                            toggleMemoCheckboxUseCase(memo = memo, lineIndex = lineIndex, checked = checked)
                        },
                        saveImage = saveImageUseCase::saveWithCacheSyncStatus,
                    ),
                scope = viewModelScope,
            )

        val collectionUiState: StateFlow<MemoCollectionUiState> = collectionStateHolder.uiState

        val errorMessage: StateFlow<String?> = collectionStateHolder.errorMessage
        val deletingMemoIds: StateFlow<Set<String>> = collectionStateHolder.deletingMemoIds
        val rootDirectory: StateFlow<String?> = collectionStateHolder.rootDirectory
        val imageDirectory: StateFlow<String?> = collectionStateHolder.imageDirectory
        val imageMap: StateFlow<Map<String, android.net.Uri>> = collectionStateHolder.imageMap
        val appPreferences: StateFlow<AppPreferencesState> = collectionStateHolder.appPreferences
        val searchResults: StateFlow<List<Memo>> = collectionStateHolder.memos
        val searchUiModels: StateFlow<List<com.lomo.app.feature.main.MemoUiModel>> =
            collectionStateHolder.uiMemos
        val canLoadMore: StateFlow<Boolean> = collectionWindowStateHolder.canLoadMore

        fun onSearchQueryChanged(query: String) {
            _searchQuery.value = query
        }

        val onSortOptionSelected: (MemoSortOption) -> Unit = searchFilterController.onSortOptionSelected
        val onStartDateSelected: (LocalDate?) -> Unit = searchFilterController.onStartDateSelected
        val onEndDateSelected: (LocalDate?) -> Unit = searchFilterController.onEndDateSelected
        val onHasTodoChanged: (Boolean?) -> Unit = searchFilterController.onHasTodoChanged
        val onHasAttachmentChanged: (Boolean?) -> Unit = searchFilterController.onHasAttachmentChanged
        val onHasUrlChanged: (Boolean?) -> Unit = searchFilterController.onHasUrlChanged
        val clearSearchFilter: () -> Unit = searchFilterController.clear

        fun deleteMemo(memo: Memo) {
            collectionStateHolder.actions.delete(memo)
        }

        fun onDeleteAnimationSettled(memoId: String) {
            collectionStateHolder.actions.onDeleteAnimationSettled(memoId)
        }

        fun updateMemo(
            memo: Memo,
            newContent: String,
        ) {
            collectionStateHolder.actions.updateMemo(memo, newContent)
        }

        fun toggleTodo(
            memo: Memo,
            lineIndex: Int,
            checked: Boolean,
        ) {
            collectionStateHolder.actions.toggleTodo(memo, lineIndex, checked)
        }

        fun saveImage(
            uri: android.net.Uri,
            onResult: (String) -> Unit,
            onError: (() -> Unit)? = null,
        ) {
            collectionStateHolder.actions.saveImage(uri, onResult, onError)
        }

        fun clearError() {
            collectionStateHolder.errors.clear()
        }

        fun loadMore() {
            collectionWindowStateHolder.loadNextPage()
        }

        fun recordMemoActionUsage(actionId: MemoActionId) {
            viewModelScope.launch {
                appConfigUiCoordinator.recordMemoActionUsage(
                    scope = MemoActionOrderScopes.SEARCH,
                    actionId = actionId.storageKey,
                )
            }
        }

        val updateMemoActionOrder: (List<MemoActionId>) -> Unit = { actionIds ->
            viewModelScope.launch {
                appConfigUiCoordinator.updateMemoActionOrder(
                    scope = MemoActionOrderScopes.SEARCH,
                    order = actionIds.map(MemoActionId::storageKey),
                )
            }
        }

        val updateInputToolbarToolOrder: (List<String>) -> Unit = { toolIds ->
            viewModelScope.launch {
                appConfigUiCoordinator.updateInputToolbarToolOrder(toolIds)
            }
        }

        private data class SearchQueryInput(
            val query: String,
            val filter: MemoListFilter,
        )

        init {
            reportSearchLoadError = { throwable ->
                collectionStateHolder.errors.report(throwable, "Failed to search memos")
            }
        }
    }

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private fun Flow<Boolean>.toDelayedSearchLoading() =
    channelFlow {
        var loadingVisible = false
        var loadingMinimumVisibleJob: Job? = null

        collectLatest { loading ->
            if (loading) {
                delay(SEARCH_LOADING_SHOW_DELAY_MILLIS)
                loadingVisible = true
                loadingMinimumVisibleJob =
                    this@channelFlow.launch {
                        delay(SEARCH_LOADING_MIN_VISIBLE_MILLIS)
                    }
                send(true)
                awaitCancellation()
            } else {
                if (loadingVisible) {
                    loadingMinimumVisibleJob?.join()
                }
                loadingVisible = false
                send(false)
            }
        }
    }
