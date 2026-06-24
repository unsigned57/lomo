package com.lomo.app.feature.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.AppConfigStateProvider
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoActionOrderScopes
import com.lomo.app.feature.common.UiState
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.app.feature.common.toUserMessage
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.feature.main.mapToUiModels
import com.lomo.app.feature.memo.MemoActionId
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.DailyReviewCollectionSource
import com.lomo.domain.model.DailyReviewSession
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.usecase.DailyReviewQueryUseCase
import com.lomo.domain.usecase.DailyReviewSessionUseCase
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.ObserveActiveDayCountUseCase
import com.lomo.domain.usecase.SaveImageResult
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.ToggleMemoCheckboxUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DailyReviewViewModel
    @Inject
    constructor(
        private val observeActiveDayCountUseCase: ObserveActiveDayCountUseCase,
        private val appConfigStateProvider: AppConfigStateProvider,
        private val appConfigUiCoordinator: AppConfigUiCoordinator,
        private val imageMapProvider: ImageMapProvider,
        private val memoUiMapper: MemoUiMapper,
        private val deleteMemoUseCase: DeleteMemoUseCase,
        private val updateMemoContentUseCase: UpdateMemoContentUseCase,
        private val toggleMemoCheckboxUseCase: ToggleMemoCheckboxUseCase,
        private val saveImageUseCase: SaveImageUseCase,
        private val dailyReviewQueryUseCase: DailyReviewQueryUseCase,
        private val dailyReviewSessionUseCase: DailyReviewSessionUseCase,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<UiState<List<com.lomo.app.feature.main.MemoUiModel>>>(UiState.Loading)
        val uiState: StateFlow<UiState<List<com.lomo.app.feature.main.MemoUiModel>>> = _uiState.asStateFlow()
        private val rawMemos = MutableStateFlow<List<Memo>?>(null)
        private val _isLoadingMore = MutableStateFlow(false)
        val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()
        private val _restoredPageIndex = MutableStateFlow(0)
        val restoredPageIndex: StateFlow<Int> = _restoredPageIndex.asStateFlow()
        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
        private var loadJob: Job? = null
        private var canLoadMore = true
        private var currentSession: DailyReviewSession? = null
        private var currentCollectionSource: DailyReviewCollectionSource? = null

        val appPreferences: StateFlow<AppPreferencesState> = appConfigStateProvider.appPreferences

        val activeDayCount: StateFlow<Int> =
            observeActiveDayCountUseCase()
                .stateIn(viewModelScope, appWhileSubscribed(), 0)

        val rootDirectory: StateFlow<String?> = appConfigStateProvider.rootDirectory

        val imageDirectory: StateFlow<String?> = appConfigStateProvider.imageDirectory

        val imageMap: StateFlow<Map<String, android.net.Uri>> = imageMapProvider.imageMap

        init {
            observeMappedUiModels()
            loadDailyReview()
        }

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        private fun observeMappedUiModels() {
            viewModelScope.launch {
                rawMemos.filterNotNull()
                    .mapToUiModels(
                        rootDirectory = rootDirectory,
                        imageDirectory = imageDirectory,
                        imageMap = imageMapProvider.imageMap,
                        memoUiMapper = memoUiMapper,
                    ).collect { uiModels ->
                        _uiState.value = UiState.Success(uiModels)
                    }
            }
        }

        private fun loadDailyReview() {
            loadJob?.cancel()
            loadJob =
                viewModelScope.launch {
                    _uiState.value = UiState.Loading
                    canLoadMore = true
                    runCatching {
                        val session = dailyReviewSessionUseCase.prepareSession()
                        val page =
                            dailyReviewQueryUseCase.loadPage(
                                DailyReviewCollectionSource.fromSession(session),
                            )
                        session to page
                    }.onFailure { throwable ->
                        if (throwable is kotlinx.coroutines.CancellationException) {
                            throw throwable
                        }
                        _uiState.value = UiState.Error("Failed to load daily review", throwable)
                    }.onSuccess { (session, page) ->
                        val memos = page.memos
                        currentSession = session
                        currentCollectionSource = page.nextSource
                        rawMemos.value = memos
                        canLoadMore = memos.isNotEmpty()
                        val clampedPageIndex = session.pageIndex.coerceIn(0, memos.lastIndex.coerceAtLeast(0))
                        _restoredPageIndex.value = clampedPageIndex
                        if (clampedPageIndex != session.pageIndex) {
                            currentSession = session.copy(pageIndex = clampedPageIndex)
                            dailyReviewSessionUseCase.updateCurrentPage(
                                seed = session.seed,
                                pageIndex = clampedPageIndex,
                            )
                        }
                    }
                }
        }

        fun loadMore() {
            val currentMemos = rawMemos.value ?: return
            val session = currentSession ?: return
            val source = currentCollectionSource ?: DailyReviewCollectionSource.fromSession(session)
            if (!canLoadMore || loadJob?.isActive == true || _isLoadingMore.value) {
                return
            }

            loadJob =
                viewModelScope.launch {
                    _isLoadingMore.value = true
                    runCatching {
                        dailyReviewQueryUseCase.loadPage(source)
                    }.onFailure { throwable ->
                        if (throwable is kotlinx.coroutines.CancellationException) {
                            throw throwable
                        }
                        _errorMessage.value = throwable.toUserMessage("Failed to load more memos")
                    }.onSuccess { page ->
                        val newMemos = page.memos
                        currentCollectionSource = page.nextSource
                        if (newMemos.isEmpty()) {
                            canLoadMore = false
                        } else {
                            val latestMemos = rawMemos.value.orEmpty()
                            rawMemos.value =
                                mergeLoadedMemos(
                                    visibleAtRequestStart = currentMemos,
                                    latestVisibleMemos = latestMemos,
                                    loadedMemos = newMemos,
                                )
                        }
                    }
                    _isLoadingMore.value = false
                }
        }

        private fun mergeLoadedMemos(
            visibleAtRequestStart: List<Memo>,
            latestVisibleMemos: List<Memo>,
            loadedMemos: List<Memo>,
        ): List<Memo> {
            val latestIds = latestVisibleMemos.mapTo(linkedSetOf()) { memo -> memo.id }
            val removedDuringRequestIds =
                visibleAtRequestStart
                    .asSequence()
                    .map { memo -> memo.id }
                    .filterNot { id -> id in latestIds }
                    .toSet()
            val appendableMemos =
                loadedMemos.filterNot { memo ->
                    memo.id in latestIds || memo.id in removedDuringRequestIds
                }
            return latestVisibleMemos + appendableMemos
        }

        fun onPageChanged(pageIndex: Int) {
            val session = currentSession ?: return
            val normalizedPageIndex = pageIndex.coerceAtLeast(0)
            _restoredPageIndex.value = normalizedPageIndex
            currentSession = session.copy(pageIndex = normalizedPageIndex)
            viewModelScope.launch {
                dailyReviewSessionUseCase.updateCurrentPage(
                    seed = session.seed,
                    pageIndex = normalizedPageIndex,
                )
            }
        }

        fun updateMemo(
            memo: Memo,
            newContent: String,
        ) {
            viewModelScope.launch {
                runCatching {
                    updateMemoContentUseCase(memo, newContent)
                }.onSuccess {
                    rawMemos.value =
                        rawMemos.value?.map { current ->
                            if (current.id == memo.id) {
                                current.copy(
                                    content = newContent,
                                    rawContent = newContent,
                                )
                            } else {
                                current
                            }
                        }
                }.onFailure { throwable ->
                    if (throwable is kotlinx.coroutines.CancellationException) {
                        throw throwable
                    }
                    _errorMessage.value = throwable.toUserMessage("Failed to update memo")
                }
            }
        }

        fun toggleTodo(
            memo: Memo,
            lineIndex: Int,
            checked: Boolean,
        ) {
            viewModelScope.launch {
                runCatching {
                    toggleMemoCheckboxUseCase(memo, lineIndex, checked)
                }.onSuccess { newContent ->
                    // The review list is a frozen random-walk snapshot, so mirror the persisted
                    // toggle into rawMemos optimistically (same pattern as updateMemo/deleteMemo).
                    if (newContent != null) {
                        rawMemos.value =
                            rawMemos.value?.map { current ->
                                if (current.id == memo.id) {
                                    current.copy(
                                        content = newContent,
                                        rawContent = newContent,
                                    )
                                } else {
                                    current
                                }
                            }
                    }
                }.onFailure { throwable ->
                    if (throwable is kotlinx.coroutines.CancellationException) {
                        throw throwable
                    }
                    _errorMessage.value = throwable.toUserMessage("Failed to update todo")
                }
            }
        }

        fun deleteMemo(memo: Memo, anchoredAfterKey: String?) {
            anchoredAfterKey?.let {
                // behavior-contract: silent-result-ok: no-op for non-animated daily review list
            }
            viewModelScope.launch {
                runCatching {
                    deleteMemoUseCase(memo)
                }.onSuccess {
                    rawMemos.value =
                        rawMemos.value?.filterNot { current ->
                            current.id == memo.id
                        }
                }.onFailure { throwable ->
                    if (throwable is kotlinx.coroutines.CancellationException) {
                        throw throwable
                    }
                    _errorMessage.value = throwable.toUserMessage("Failed to delete memo")
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

        fun recordMemoActionUsage(actionId: MemoActionId) {
            viewModelScope.launch {
                appConfigUiCoordinator.recordMemoActionUsage(
                    scope = MemoActionOrderScopes.REVIEW,
                    actionId = actionId.storageKey,
                )
            }
        }

        val updateMemoActionOrder: (List<MemoActionId>) -> Unit = { actionIds ->
            viewModelScope.launch {
                appConfigUiCoordinator.updateMemoActionOrder(
                    scope = MemoActionOrderScopes.REVIEW,
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
