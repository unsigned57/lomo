package com.lomo.app.feature.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.AppConfigStateProvider
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.app.feature.common.newMemoOperationId
import com.lomo.app.feature.common.reportUserFailure
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.feature.main.mapToUiModels
import com.lomo.app.feature.memo.MemoEditorUpdateSubmission
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.util.runSuspendCatching
import com.lomo.domain.model.DailyReviewCollectionSource
import com.lomo.domain.model.DailyReviewOptions
import com.lomo.domain.model.DailyReviewSession
import com.lomo.domain.model.EngineDiagnosticsRecorder
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.usecase.DailyReviewQueryUseCase
import com.lomo.domain.usecase.DailyReviewSessionUseCase
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.ObserveActiveDayCountUseCase
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface DailyReviewScreenState {
    data object Loading : DailyReviewScreenState

    data class Ready(
        val memos: List<MemoUiModel>,
        val isLoadingMore: Boolean,
        val pageIndex: Int,
        val errorMessage: String?,
        val options: DailyReviewOptions = DailyReviewOptions(),
    ) : DailyReviewScreenState

    data class Failed(val message: String, val throwable: Throwable? = null) : DailyReviewScreenState
}

/** Collaborators of the daily-review screen. */
data class DailyReviewViewModelDependencies(
    val diagnostics: EngineDiagnosticsRecorder,
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

class DailyReviewViewModel(dependencies: DailyReviewViewModelDependencies) : ViewModel() {
    private val diagnostics = dependencies.diagnostics
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
    private val reviewData = MutableStateFlow<DailyReviewData>(DailyReviewData.Loading)
    val restoredPageIndex: StateFlow<Int> = reviewData.map { publication ->
        when (publication) {
            is DailyReviewData.Loaded -> publication.session.pageIndex
            DailyReviewData.Loading, is DailyReviewData.Failed -> 0
        }
    }.stateIn(viewModelScope, appWhileSubscribed(), 0)
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    private val memoUpdater = DailyReviewMemoUpdater(updateMemoContentUseCase, ::updateVisibleMemoContent)
    private val draftId = com.lomo.domain.model.DraftId.mint()
    internal val editorSubmission =
        MemoEditorUpdateSubmission(
            draftId = { draftId },
            scope = viewModelScope,
            updateMemo = memoUpdater::update,
            onFailure = { throwable ->
                _errorMessage.value = diagnostics.reportUserFailure("review.update", throwable, "Failed to update memo")
            },
        )
    private var loadJob: Job? = null

    val appPreferences: StateFlow<AppPreferencesState> = appConfigStateProvider.appPreferences

    val activeDayCount: StateFlow<Int> =
        observeActiveDayCountUseCase()
            .stateIn(viewModelScope, appWhileSubscribed(), 0)

    val rootDirectory: StateFlow<String?> = appConfigStateProvider.rootDirectory

    val imageDirectory: StateFlow<String?> = appConfigStateProvider.imageDirectory

    val imageMap: StateFlow<Map<String, android.net.Uri>> = imageMapProvider.imageMap

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val mappedMemos: StateFlow<List<MemoUiModel>> =
        reviewData.map { publication ->
            when (publication) {
                is DailyReviewData.Loaded -> publication.memos
                DailyReviewData.Loading, is DailyReviewData.Failed -> emptyList()
            }
        }
            .mapToUiModels(
                rootDirectory = rootDirectory,
                imageDirectory = imageDirectory,
                imageMap = imageMapProvider.imageMap,
                memoUiMapper = memoUiMapper,
            ).stateIn(viewModelScope, appWhileSubscribed(), emptyList())

    val uiState: StateFlow<DailyReviewScreenState> =
        combine(reviewData, mappedMemos, _errorMessage) { publication, mapped, snackbar ->
            when (publication) {
                DailyReviewData.Loading -> DailyReviewScreenState.Loading

                is DailyReviewData.Failed -> publication.failure

                is DailyReviewData.Loaded -> {
                    if (publication.memos.map { it.id } != mapped.map { it.memo.id }) {
                        DailyReviewScreenState.Loading
                    } else {
                        DailyReviewScreenState.Ready(
                            memos = mapped,
                            isLoadingMore = publication.isLoadingMore,
                            pageIndex = publication.session.pageIndex,
                            errorMessage = snackbar,
                            options = publication.session.options,
                        )
                    }
                }
            }
        }.stateIn(viewModelScope, appWhileSubscribed(), DailyReviewScreenState.Loading)

    init {
        loadDailyReview()
    }

    val updateReviewOptions: (DailyReviewOptions) -> Unit = { options -> loadDailyReview(options) }
    val retry: () -> Unit = { loadDailyReview() }

    private fun loadDailyReview(options: DailyReviewOptions? = null) {
        loadJob?.cancel()
        loadJob =
            viewModelScope.launch {
                reviewData.value = DailyReviewData.Loading
                runSuspendCatching {
                    val restored = dailyReviewSessionUseCase.prepareSession()
                    val session = if (options == null || options == restored.options) {
                        restored
                    } else {
                        restored.copy(pageIndex = 0, options = options)
                    }
                    if (session != restored) {
                        dailyReviewSessionUseCase.updateCurrentPage(session.seed, 0, session.options)
                    }
                    val page =
                        dailyReviewQueryUseCase.loadPage(
                            DailyReviewCollectionSource.fromSession(session),
                        )
                    session to page
                }.onFailure { throwable ->
                    reviewData.value = DailyReviewData.Failed(
                        DailyReviewScreenState.Failed("Failed to load daily review", throwable),
                    )
                }.onSuccess { (session, page) ->
                    val memos = page.memos
                    val clampedPageIndex = session.pageIndex.coerceIn(0, memos.lastIndex.coerceAtLeast(0))
                    reviewData.value = DailyReviewData.Loaded(
                        memos = memos,
                        session = session.copy(pageIndex = clampedPageIndex),
                        source = page.nextSource,
                    )
                    if (clampedPageIndex != session.pageIndex) {
                        dailyReviewSessionUseCase.updateCurrentPage(
                            seed = session.seed,
                            pageIndex = clampedPageIndex,
                            options = session.options,
                        )
                    }
                }
            }
    }

    fun loadMore() {
        val current = reviewData.value as? DailyReviewData.Loaded ?: return
        if (current.source.exhausted || loadJob?.isActive == true || current.isLoadingMore) return
        loadJob = viewModelScope.launch {
            reviewData.value = current.copy(isLoadingMore = true)
            runSuspendCatching {
                dailyReviewQueryUseCase.loadPage(current.source)
            }.onFailure { throwable ->
                reviewData.update { latest ->
                    if (latest is DailyReviewData.Loaded) latest.copy(isLoadingMore = false) else latest
                }
                _errorMessage.value = diagnostics.reportUserFailure(
                    "review.load_more",
                    throwable,
                    "Failed to load more memos",
                )
            }.onSuccess { page ->
                reviewData.update { latest ->
                    if (latest is DailyReviewData.Loaded) {
                        latest.copy(
                            memos = mergeLoadedMemos(current.memos, latest.memos, page.memos),
                            source = page.nextSource,
                            isLoadingMore = false,
                        )
                    } else {
                        latest
                    }
                }
            }
        }
    }

    fun onPageChanged(pageIndex: Int) {
        val current = reviewData.value as? DailyReviewData.Loaded ?: return
        val session = current.session.copy(pageIndex = pageIndex.coerceAtLeast(0))
        reviewData.value = current.copy(session = session)
        viewModelScope.launch {
            dailyReviewSessionUseCase.updateCurrentPage(session.seed, session.pageIndex, session.options)
        }
    }

    private fun updateVisibleMemoContent(memoId: String, content: String) {
        reviewData.update { publication ->
            if (publication is DailyReviewData.Loaded) {
                publication.copy(
                    memos = publication.memos.map { memo ->
                        if (memo.id == memoId) memo.copy(content = content, rawContent = content) else memo
                    },
                )
            } else {
                publication
            }
        }
    }

    fun toggleTodo(memo: Memo, actionSpan: com.lomo.domain.model.markdown.MarkdownSourceSpan) {
        viewModelScope.launch {
            runSuspendCatching {
                toggleMemoCheckboxUseCase(memo, actionSpan)
            }.onSuccess { newContent ->
                updateVisibleMemoContent(memo.id, newContent)
            }.onFailure { throwable ->
                _errorMessage.value = diagnostics.reportUserFailure(
                    "review.toggle_task",
                    throwable,
                    "Failed to update todo",
                )
            }
        }
    }

    fun deleteMemo(memo: Memo, anchoredAfterKey: String?) {
        anchoredAfterKey?.let {
            // behavior-contract: silent-result-ok: no-op for non-animated daily review list
        }
        val operationId = newMemoOperationId()
        viewModelScope.launch {
            runSuspendCatching {
                deleteMemoUseCase(memo, operationId)
            }.onSuccess {
                reviewData.update { publication ->
                    if (publication is DailyReviewData.Loaded) {
                        publication.copy(memos = publication.memos.filterNot { it.id == memo.id })
                    } else {
                        publication
                    }
                }
            }.onFailure { throwable ->
                _errorMessage.value = diagnostics.reportUserFailure("review.delete", throwable, "Failed to delete memo")
            }
        }
    }

    fun saveImages(
        uris: List<android.net.Uri>,
        draftId: com.lomo.domain.model.DraftId,
        onResult: (List<String>) -> Unit,
        onError: (() -> Unit)? = null,
    ) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val savedPaths = mutableListOf<String>()
            runSuspendCatching {
                for (uri in uris) {
                    val path =
                        saveImageUseCase.saveWithCacheSyncStatus(
                            StorageLocation(uri.toString()),
                            draftId,
                        ).location.raw
                    savedPaths += path
                }
                onResult(savedPaths)
            }.onFailure { throwable ->
                if (savedPaths.isNotEmpty()) {
                    onResult(savedPaths)
                }
                _errorMessage.value = diagnostics.reportUserFailure(
                    "review.save_image",
                    throwable,
                    "Failed to save image",
                )
                onError?.invoke()
            }
        }
    }

    fun saveImage(
        uri: android.net.Uri,
        draftId: com.lomo.domain.model.DraftId,
        onResult: (String) -> Unit,
        onError: (() -> Unit)? = null,
    ) {
        saveImages(
            uris = listOf(uri),
            draftId = draftId,
            onResult = { paths -> paths.firstOrNull()?.let(onResult) },
            onError = onError,
        )
    }

    /** The draft identity this surface's media leases belong to. */
    internal val ownerDraftId: com.lomo.domain.model.DraftId
        get() = draftId

    /** Surfaces a non-mutation failure (e.g. editor open) on the shared error channel. */
    fun reportError(throwable: Throwable) {
        _errorMessage.value = diagnostics.reportUserFailure("review.open_memo", throwable, "Failed to open memo")
    }

    fun clearError() {
        _errorMessage.value = null
    }

    val updateInputToolbarToolOrder: (List<String>) -> Unit = { toolIds ->
        viewModelScope.launch {
            appConfigUiCoordinator.updateInputToolbarToolOrder(toolIds)
        }
    }
}

private sealed interface DailyReviewData {
    data object Loading : DailyReviewData
    data class Failed(val failure: DailyReviewScreenState.Failed) : DailyReviewData
    data class Loaded(
        val memos: List<Memo>,
        val session: DailyReviewSession,
        val source: DailyReviewCollectionSource,
        val isLoadingMore: Boolean = false,
    ) : DailyReviewData
}

private class DailyReviewMemoUpdater(
    private val updateMemoContentUseCase: UpdateMemoContentUseCase,
    private val onCommitted: (String, String) -> Unit,
) {
    suspend fun update(attempt: com.lomo.domain.model.MemoUpdateAttempt) {
        updateMemoContentUseCase(attempt)
        onCommitted(attempt.snapshot.memo.id, attempt.content)
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
