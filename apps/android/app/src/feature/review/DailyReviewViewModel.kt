package com.lomo.app.feature.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.AppConfigStateProvider
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoActionOrderScopes
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.app.feature.common.newMemoOperationId
import com.lomo.app.feature.common.toUserMessage
import com.lomo.app.util.runSuspendCatching
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.feature.main.mapToUiModels
import com.lomo.app.feature.memo.MemoActionId
import com.lomo.app.feature.memo.MemoEditorUpdateSubmission
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface DailyReviewScreenState {
    data object Loading : DailyReviewScreenState

    data class Ready(
        val memos: List<MemoUiModel>,
        val isLoadingMore: Boolean,
        val pageIndex: Int,
        val errorMessage: String?,
    ) : DailyReviewScreenState

    data class Failed(
        val message: String,
        val throwable: Throwable? = null,
    ) : DailyReviewScreenState
}

/** Collaborators of the daily-review screen. */
data class DailyReviewViewModelDependencies(
    val observeActiveDayCountUseCase: ObserveActiveDayCountUseCase,
    val appConfigStateProvider: AppConfigStateProvider,
    val appConfigUiCoordinator: AppConfigUiCoordinator,
    val imageMapProvider: ImageMapProvider,
    val memoUiMapper: MemoUiMapper,
    val deleteMemoUseCase: DeleteMemoUseCase,
    val updateMemoContentUseCase: UpdateMemoContentUseCase,
    val toggleMemoCheckboxUseCase: ToggleMemoCheckboxUseCase,
    val saveImageUseCase: SaveImageUseCase,
    val dailyReviewQueryUseCase: DailyReviewQueryUseCase,
    val dailyReviewSessionUseCase: DailyReviewSessionUseCase,
)

class DailyReviewViewModel(
    dependencies: DailyReviewViewModelDependencies,
) : ViewModel() {
    private val observeActiveDayCountUseCase = dependencies.observeActiveDayCountUseCase
    private val appConfigStateProvider = dependencies.appConfigStateProvider
    private val appConfigUiCoordinator = dependencies.appConfigUiCoordinator
    private val imageMapProvider = dependencies.imageMapProvider
    private val memoUiMapper = dependencies.memoUiMapper
    private val deleteMemoUseCase = dependencies.deleteMemoUseCase
    private val updateMemoContentUseCase = dependencies.updateMemoContentUseCase
    private val toggleMemoCheckboxUseCase = dependencies.toggleMemoCheckboxUseCase
    private val saveImageUseCase = dependencies.saveImageUseCase
    private val dailyReviewQueryUseCase = dependencies.dailyReviewQueryUseCase
    private val dailyReviewSessionUseCase = dependencies.dailyReviewSessionUseCase
    private val rawMemos = MutableStateFlow<List<Memo>?>(null)
    private val _isLoadingMore = MutableStateFlow(false)
    private val _restoredPageIndex = MutableStateFlow(0)
    val restoredPageIndex: StateFlow<Int> = _restoredPageIndex.asStateFlow()
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    private val loadFailure = MutableStateFlow<DailyReviewScreenState.Failed?>(null)
    private val memoUpdater = DailyReviewMemoUpdater(updateMemoContentUseCase, rawMemos)
    private val draftId = com.lomo.app.feature.common.newDraftId()
    internal val editorSubmission =
        MemoEditorUpdateSubmission(
            draftId = draftId,
            scope = viewModelScope,
            updateMemo = memoUpdater::update,
            onFailure = { throwable ->
                _errorMessage.value = throwable.toUserMessage("Failed to update memo")
            },
        )
    private var loadJob: Job? = null
    private val loadCursor = MutableStateFlow(DailyReviewLoadCursor())

    val appPreferences: StateFlow<AppPreferencesState> = appConfigStateProvider.appPreferences

    val activeDayCount: StateFlow<Int> =
        observeActiveDayCountUseCase()
            .stateIn(viewModelScope, appWhileSubscribed(), 0)

    val rootDirectory: StateFlow<String?> = appConfigStateProvider.rootDirectory

    val imageDirectory: StateFlow<String?> = appConfigStateProvider.imageDirectory

    val imageMap: StateFlow<Map<String, android.net.Uri>> = imageMapProvider.imageMap

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val mappedMemos: StateFlow<List<MemoUiModel>> =
        rawMemos
            .map { memos -> memos.orEmpty() }
            .mapToUiModels(
                rootDirectory = rootDirectory,
                imageDirectory = imageDirectory,
                imageMap = imageMapProvider.imageMap,
                memoUiMapper = memoUiMapper,
            ).stateIn(viewModelScope, appWhileSubscribed(), emptyList())

    val uiState: StateFlow<DailyReviewScreenState> =
        combine(
            rawMemos,
            mappedMemos,
            _isLoadingMore,
            _restoredPageIndex,
            _errorMessage,
        ) { raw, mapped, loadingMore, pageIndex, snackbar ->
            DailyReviewScreenSlice(
                raw = raw,
                mapped = mapped,
                isLoadingMore = loadingMore,
                pageIndex = pageIndex,
                errorMessage = snackbar,
            )
        }.combine(loadFailure) { slice, failed ->
            when {
                failed != null && slice.raw == null -> failed
                slice.raw == null -> DailyReviewScreenState.Loading
                else ->
                    DailyReviewScreenState.Ready(
                        memos = slice.mapped,
                        isLoadingMore = slice.isLoadingMore,
                        pageIndex = slice.pageIndex,
                        errorMessage = slice.errorMessage,
                    )
            }
        }.stateIn(viewModelScope, appWhileSubscribed(), DailyReviewScreenState.Loading)

    init {
        loadDailyReview()
    }

    private fun loadDailyReview() {
        loadJob?.cancel()
        loadJob =
            viewModelScope.launch {
                rawMemos.value = null
                loadFailure.value = null
                loadCursor.value = DailyReviewLoadCursor()
                runSuspendCatching {
                    val session = dailyReviewSessionUseCase.prepareSession()
                    val page =
                        dailyReviewQueryUseCase.loadPage(
                            DailyReviewCollectionSource.fromSession(session),
                        )
                    session to page
                }.onFailure { throwable ->
                    loadFailure.value =
                        DailyReviewScreenState.Failed("Failed to load daily review", throwable)
                }.onSuccess { (session, page) ->
                    val memos = page.memos
                    loadCursor.value =
                        DailyReviewLoadCursor(
                            canLoadMore = memos.isNotEmpty(),
                            session = session,
                            collectionSource = page.nextSource,
                        )
                    rawMemos.value = memos
                    val clampedPageIndex = session.pageIndex.coerceIn(0, memos.lastIndex.coerceAtLeast(0))
                    _restoredPageIndex.value = clampedPageIndex
                    if (clampedPageIndex != session.pageIndex) {
                        loadCursor.value =
                            loadCursor.value.copy(session = session.copy(pageIndex = clampedPageIndex))
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
        val cursor = loadCursor.value
        val session = cursor.session ?: return
        val source = cursor.collectionSource ?: DailyReviewCollectionSource.fromSession(session)
        if (!cursor.canLoadMore || loadJob?.isActive == true || _isLoadingMore.value) {
            return
        }

        loadJob =
            viewModelScope.launch {
                _isLoadingMore.value = true
                runSuspendCatching {
                    dailyReviewQueryUseCase.loadPage(source)
                }.onFailure { throwable ->
                    _errorMessage.value = throwable.toUserMessage("Failed to load more memos")
                }.onSuccess { page ->
                    val newMemos = page.memos
                    loadCursor.value =
                        loadCursor.value.copy(
                            collectionSource = page.nextSource,
                            canLoadMore = newMemos.isNotEmpty() && loadCursor.value.canLoadMore,
                        )
                    if (newMemos.isNotEmpty()) {
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
        val session = loadCursor.value.session ?: return
        val normalizedPageIndex = pageIndex.coerceAtLeast(0)
        _restoredPageIndex.value = normalizedPageIndex
        loadCursor.value = loadCursor.value.copy(session = session.copy(pageIndex = normalizedPageIndex))
        viewModelScope.launch {
            dailyReviewSessionUseCase.updateCurrentPage(
                seed = session.seed,
                pageIndex = normalizedPageIndex,
            )
        }
    }

    fun toggleTodo(
        memo: Memo,
        actionSpan: com.lomo.domain.model.markdown.MarkdownSourceSpan,
    ) {
        viewModelScope.launch {
            runSuspendCatching {
                toggleMemoCheckboxUseCase(memo, actionSpan)
            }.onSuccess { newContent ->
                // The review list is a frozen random-walk snapshot, so mirror the persisted
                // toggle into rawMemos optimistically (same pattern as updateMemo/deleteMemo).
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
                _errorMessage.value = throwable.toUserMessage("Failed to update todo")
            }
        }
    }

    fun deleteMemo(
        memo: Memo,
        anchoredAfterKey: String?,
    ) {
        anchoredAfterKey?.let {
            // behavior-contract: silent-result-ok: no-op for non-animated daily review list
        }
        val operationId = newMemoOperationId()
        viewModelScope.launch {
            runSuspendCatching {
                deleteMemoUseCase(memo, operationId)
            }.onSuccess {
                rawMemos.value =
                    rawMemos.value?.filterNot { current ->
                        current.id == memo.id
                    }
            }.onFailure { throwable ->
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
            runSuspendCatching {
                val path =
                    saveImageUseCase.saveWithCacheSyncStatus(
                        StorageLocation(uri.toString()),
                        draftId,
                    ).location.raw
                onResult(path)
            }.onFailure { throwable ->
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

private data class DailyReviewScreenSlice(
    val raw: List<Memo>?,
    val mapped: List<MemoUiModel>,
    val isLoadingMore: Boolean,
    val pageIndex: Int,
    val errorMessage: String?,
)

private data class DailyReviewLoadCursor(
    val canLoadMore: Boolean = true,
    val session: DailyReviewSession? = null,
    val collectionSource: DailyReviewCollectionSource? = null,
)

private class DailyReviewMemoUpdater(
    private val updateMemoContentUseCase: UpdateMemoContentUseCase,
    private val rawMemos: MutableStateFlow<List<Memo>?>,
) {
    suspend fun update(
        attempt: com.lomo.domain.model.MemoUpdateAttempt,
    ) {
        val memo = attempt.snapshot.memo
        val newContent = attempt.content
        updateMemoContentUseCase(attempt)
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
}
