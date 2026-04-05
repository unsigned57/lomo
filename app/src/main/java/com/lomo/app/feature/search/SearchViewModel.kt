package com.lomo.app.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoUiCoordinator
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.app.feature.common.toUserMessage
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.SaveImageResult
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val SEARCH_QUERY_DEBOUNCE_MILLIS = 300L
private const val SEARCH_LOADING_SHOW_DELAY_MILLIS = 120L
private const val SEARCH_LOADING_MIN_VISIBLE_MILLIS = 280L

@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        private val memoUiCoordinator: MemoUiCoordinator,
        private val appConfigUiCoordinator: AppConfigUiCoordinator,
        private val imageMapProvider: ImageMapProvider,
        private val memoUiMapper: MemoUiMapper,
        private val deleteMemoUseCase: DeleteMemoUseCase,
        private val updateMemoContentUseCase: UpdateMemoContentUseCase,
        private val saveImageUseCase: SaveImageUseCase,
    ) : ViewModel() {
        private val _searchQuery = MutableStateFlow("")
        val searchQuery: StateFlow<String> = _searchQuery
        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

        val rootDirectory: StateFlow<String?> =
            appConfigUiCoordinator
                .rootDirectory()
                .stateIn(viewModelScope, appWhileSubscribed(), null)

        val imageDirectory: StateFlow<String?> =
            appConfigUiCoordinator
                .imageDirectory()
                .stateIn(viewModelScope, appWhileSubscribed(), null)

        val imageMap: StateFlow<Map<String, android.net.Uri>> = imageMapProvider.imageMap

        val appPreferences: StateFlow<AppPreferencesState> =
            appConfigUiCoordinator
                .appPreferences()
                .stateIn(viewModelScope, appWhileSubscribed(), AppPreferencesState.defaults())

        val activeDayCount: StateFlow<Int> =
            memoUiCoordinator
                .activeDayCount()
                .stateIn(viewModelScope, appWhileSubscribed(), 0)

        @OptIn(ExperimentalCoroutinesApi::class)
        private val searchExecutionState: StateFlow<SearchExecutionState> =
            _searchQuery
                .transformLatest { rawQuery ->
                    val query = rawQuery.trim()
                    if (query.isBlank()) {
                        emit(SearchExecutionState())
                        return@transformLatest
                    }

                    delay(SEARCH_QUERY_DEBOUNCE_MILLIS)
                    emitAll(searchExecutionFlow(query))
                }.stateIn(viewModelScope, appWhileSubscribed(), SearchExecutionState())

        val isSearching: StateFlow<Boolean> =
            searchExecutionState
                .map { it.isSearching }
                .stateIn(viewModelScope, appWhileSubscribed(), false)

        val searchResults: StateFlow<List<Memo>> =
            searchExecutionState
                .map { it.results }
                .stateIn(viewModelScope, appWhileSubscribed(), emptyList())

        val showLoading: StateFlow<Boolean> =
            searchExecutionState
                .map { it.showLoading }
                .stateIn(viewModelScope, appWhileSubscribed(), false)

        @OptIn(ExperimentalCoroutinesApi::class)
        val searchUiModels: StateFlow<List<com.lomo.app.feature.main.MemoUiModel>> =
            combine(searchResults, rootDirectory, imageDirectory, imageMap) {
                memos,
                rootDir,
                imageDir,
                currentImageMap,
                ->
                UiMemoMappingInput(
                    memos = memos,
                    rootDirectory = rootDir,
                    imageDirectory = imageDir,
                    imageMap = currentImageMap,
                )
            }.distinctUntilChanged()
                .mapLatest { input ->
                    memoUiMapper.mapToUiModels(
                        memos = input.memos,
                        rootPath = input.rootDirectory,
                        imagePath = input.imageDirectory,
                        imageMap = input.imageMap,
                    )
                }.stateIn(viewModelScope, appWhileSubscribed(), emptyList())

        fun onSearchQueryChanged(query: String) {
            _searchQuery.value = query
        }

        fun deleteMemo(memo: Memo) {
            viewModelScope.launch {
                runCatching {
                    deleteMemoUseCase(memo)
                }.onFailure { throwable ->
                    if (throwable is kotlinx.coroutines.CancellationException) {
                        throw throwable
                    }
                    _errorMessage.value = throwable.toUserMessage("Failed to delete memo")
                }
            }
        }

        fun updateMemo(
            memo: Memo,
            newContent: String,
        ) {
            viewModelScope.launch {
                runCatching {
                    updateMemoContentUseCase(memo, newContent)
                }.onFailure { throwable ->
                    if (throwable is kotlinx.coroutines.CancellationException) {
                        throw throwable
                    }
                    _errorMessage.value = throwable.toUserMessage("Failed to update memo")
                }
            }
        }

        fun saveImage(
            uri: android.net.Uri,
            onResult: (String) -> Unit,
            onError: (() -> Unit)? = null,
        ) {
            viewModelScope.launch {
                runCatching {
                    val path =
                        when (
                            val result =
                                saveImageUseCase.saveWithCacheSyncStatus(
                                    StorageLocation(uri.toString()),
                                )
                        ) {
                            is SaveImageResult.SavedAndCacheSynced -> result.location.raw
                            is SaveImageResult.SavedButCacheSyncFailed -> throw result.cause
                        }
                    onResult(path)
                }.onFailure { throwable ->
                    if (throwable is kotlinx.coroutines.CancellationException) {
                        throw throwable
                    }
                    _errorMessage.value = throwable.toUserMessage("Failed to save image")
                    onError?.invoke()
                }
            }
        }

        fun clearError() {
            _errorMessage.value = null
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        private fun searchExecutionFlow(query: String) =
            channelFlow searchExecution@{
                send(SearchExecutionState(isSearching = true))
                var loadingVisible = false
                var loadingMinVisibleJob: Job? = null
                val loadingJob =
                    launch {
                        delay(SEARCH_LOADING_SHOW_DELAY_MILLIS)
                        loadingVisible = true
                        loadingMinVisibleJob =
                            this@searchExecution.launch {
                                delay(SEARCH_LOADING_MIN_VISIBLE_MILLIS)
                            }
                        send(
                            SearchExecutionState(
                                isSearching = true,
                                showLoading = true,
                            ),
                        )
                    }

                memoUiCoordinator
                    .searchMemos(query)
                    .catch {
                        loadingJob.cancel()
                        if (loadingVisible) {
                            loadingMinVisibleJob?.join()
                        }
                        send(SearchExecutionState())
                    }.collectLatest { results ->
                        loadingJob.cancel()
                        if (loadingVisible) {
                            send(
                                SearchExecutionState(
                                    showLoading = true,
                                    results = results,
                                ),
                            )
                            loadingMinVisibleJob?.join()
                        }
                        send(SearchExecutionState(results = results))
                    }
            }

        private data class UiMemoMappingInput(
            val memos: List<Memo>,
            val rootDirectory: String?,
            val imageDirectory: String?,
            val imageMap: Map<String, android.net.Uri>,
        )

        private data class SearchExecutionState(
            val isSearching: Boolean = false,
            val showLoading: Boolean = false,
            val results: List<Memo> = emptyList(),
        )
    }
