package com.lomo.app.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.lomo.app.feature.common.AppConfigStateProvider
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.LoadingAwarePagingSource
import com.lomo.app.feature.common.MemoActionOrderScopes
import com.lomo.app.feature.common.MemoCollectionActionStateHolder
import com.lomo.app.feature.common.MemoCollectionCapabilities
import com.lomo.app.feature.common.MemoCollectionProjectionMapper
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.app.feature.common.memoPager
import com.lomo.app.feature.main.MemoUiModel
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
import com.lomo.app.feature.common.MemoCollectionUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

private const val SEARCH_QUERY_DEBOUNCE_MILLIS = 300L
private const val SEARCH_PAGE_SIZE = 20
private const val SEARCH_INITIAL_LOAD_SIZE = 60
private const val SEARCH_PREFETCH_DISTANCE = 10
private const val SEARCH_ENABLE_PLACEHOLDERS = true

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

        val activeDayCount: StateFlow<Int> =
            observeActiveDayCountUseCase()
                .stateIn(viewModelScope, appWhileSubscribed(), 0)

        @OptIn(FlowPreview::class)
        private val searchQueryInput =
            combine(_searchQuery, searchFilterController.filter) { rawQuery, filter ->
                SearchQueryInput(query = rawQuery.trim(), filter = filter)
            }.debounce(SEARCH_QUERY_DEBOUNCE_MILLIS)

        private val mappingInput =
            combine(
                appConfigStateProvider.rootDirectory,
                appConfigStateProvider.imageDirectory,
                imageMapProvider.imageMap,
            ) { root, img, map -> UiMappingInput(root, img, map) }
                .distinctUntilChanged { old, new -> old.sameForPaging(new) }
                .stateIn(viewModelScope, appWhileSubscribed(), UiMappingInput.EMPTY)

        @OptIn(ExperimentalCoroutinesApi::class)
        private val searchPagedMemos: StateFlow<PagingData<Memo>?> =
            searchQueryInput
                .flatMapLatest { input ->
                    if (input.query.isBlank()) {
                        flowOf(PagingData.empty())
                    } else {
                        memoPager(
                            scope = viewModelScope,
                            pageSize = SEARCH_PAGE_SIZE,
                            initialLoadSize = SEARCH_INITIAL_LOAD_SIZE,
                            prefetchDistance = SEARCH_PREFETCH_DISTANCE,
                            enablePlaceholders = SEARCH_ENABLE_PLACEHOLDERS,
                            pagingSourceFactory = {
                                LoadingAwarePagingSource(
                                    delegate = searchMemosPageUseCase.getPagingSource(
                                        query = input.query,
                                        filter = input.filter,
                                    ),
                                    onError = { throwable ->
                                        actionStateHolder.errors.report(throwable, "Failed to search memos")
                                    }
                                )
                            }
                        )
                    }
                }
                .cachedIn(viewModelScope)
                .stateIn(viewModelScope, SharingStarted.Lazily, null)

        val pagedUiMemos: Flow<PagingData<MemoUiModel>> =
            combine(
                mappingInput,
                searchPagedMemos.filterNotNull(),
            ) { mapInput, pagingData ->
                pagingData.map { memo ->
                    projectionMapper.memoUiMapper.mapToUiModel(memo, mapInput.root, mapInput.img, mapInput.map)
                }
            }

        private val actionStateHolder =
            MemoCollectionActionStateHolder(
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
                mapToUiModel = { memo ->
                    projectionMapper.memoUiMapper.mapToUiModel(
                        memo = memo,
                        rootPath = rootDirectory.value,
                        imagePath = imageDirectory.value,
                        imageMap = imageMap.value,
                    )
                }
            )

        val appPreferences: StateFlow<AppPreferencesState> = appConfigStateProvider.appPreferences
        val errorMessage: StateFlow<String?> = actionStateHolder.errorMessage
        val deletingMemoIds: StateFlow<Set<String>> = actionStateHolder.deletingMemoIds
        val exitAnimationRegistry = actionStateHolder.exitAnimationRegistry
        val collectionUiState: StateFlow<MemoCollectionUiState> = actionStateHolder.uiState
        val rootDirectory: StateFlow<String?> = appConfigStateProvider.rootDirectory
        val imageDirectory: StateFlow<String?> = appConfigStateProvider.imageDirectory
        val imageMap: StateFlow<Map<String, android.net.Uri>> = imageMapProvider.imageMap

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

        fun deleteMemo(
            memo: Memo,
            anchoredAfterKey: String?,
        ) {
            actionStateHolder.actions.delete(memo, anchoredAfterKey)
        }

        fun onDeleteAnimationSettled(memoId: String) {
            exitAnimationRegistry.markExitAnimationSettled(memoId)
        }

        fun updateMemo(
            memo: Memo,
            newContent: String,
        ) {
            actionStateHolder.actions.updateMemo(memo, newContent)
        }

        fun toggleTodo(
            memo: Memo,
            lineIndex: Int,
            checked: Boolean,
        ) {
            actionStateHolder.actions.toggleTodo(memo, lineIndex, checked)
        }

        fun saveImage(
            uri: android.net.Uri,
            onResult: (String) -> Unit,
            onError: (() -> Unit)? = null,
        ) {
            actionStateHolder.actions.saveImage(uri, onResult, onError)
        }

        fun clearError() {
            actionStateHolder.errors.clear()
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
    }


private data class SearchQueryInput(
    val query: String,
    val filter: MemoListFilter,
)

private data class UiMappingInput(
    val root: String?,
    val img: String?,
    val map: Map<String, android.net.Uri>,
) {
    fun sameForPaging(other: UiMappingInput): Boolean {
        return root == other.root && img == other.img && map == other.map
    }

    companion object {
        val EMPTY = UiMappingInput(null, null, emptyMap())
    }
}
