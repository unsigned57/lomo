package com.lomo.app.feature.main

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.lomo.app.feature.common.MemoUiCoordinator
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.usecase.ApplyMainMemoFilterUseCase
import com.lomo.domain.usecase.ResolveMainMemoQueryUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

private const val SEARCH_DEBOUNCE_MILLIS = 150L
private const val DEFAULT_MAIN_LIST_PAGE_SIZE = 30
private const val DEFAULT_MAIN_LIST_INITIAL_LOAD_SIZE = 60
private const val DEFAULT_MAIN_LIST_PREFETCH_DISTANCE = 10

internal class MainMemoListStateHolder(
    scope: CoroutineScope,
    memoUiCoordinator: MemoUiCoordinator,
    memoUiMapper: MemoUiMapper,
    searchQuery: StateFlow<String>,
    memoListFilter: StateFlow<MemoListFilter>,
    rootDirectory: StateFlow<String?>,
    imageDirectory: StateFlow<String?>,
    imageMap: StateFlow<Map<String, android.net.Uri>>,
    applyMainMemoFilterUseCase: ApplyMainMemoFilterUseCase,
    resolveMainMemoQueryUseCase: ResolveMainMemoQueryUseCase,
) {
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private val mainMemoQueryInput: StateFlow<MemoQueryInput> =
        combine(
            searchQuery.debounce(SEARCH_DEBOUNCE_MILLIS).distinctUntilChanged(),
            memoListFilter,
        ) { query: String, filter: MemoListFilter ->
            MemoQueryInput(query = query, filter = filter)
        }.stateIn(
            scope,
            appWhileSubscribed(),
            MemoQueryInput(query = "", filter = MemoListFilter()),
        )

    val mainListPresentationMode: StateFlow<MainListPresentationMode> =
        mainMemoQueryInput
            .map { input ->
                resolveMainListPresentationMode(
                    query = input.query,
                    filter = input.filter,
                )
            }.stateIn(
                scope,
                appWhileSubscribed(),
                MainListPresentationMode.PagedDefault,
            )

    val usesPagedMainList: StateFlow<Boolean> =
        mainListPresentationMode
            .map { mode -> mode == MainListPresentationMode.PagedDefault }
            .stateIn(scope, appWhileSubscribed(), true)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val memos: StateFlow<List<Memo>> =
        mainMemoQueryInput.flatMapLatest { input ->
            when (resolveMainListPresentationMode(query = input.query, filter = input.filter)) {
                MainListPresentationMode.PagedDefault -> flowOf(emptyList())
                MainListPresentationMode.FilteredList ->
                    resolveMemoFlow(
                        query = input.query,
                        filter = input.filter,
                        memoUiCoordinator = memoUiCoordinator,
                        resolveMainMemoQueryUseCase = resolveMainMemoQueryUseCase,
                    ).map { sourceMemos ->
                        applyMainMemoFilterUseCase(memos = sourceMemos, filter = input.filter)
                    }
            }
        }.stateIn(scope, appWhileSubscribed(), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val pagedUiMemos: Flow<PagingData<MemoUiModel>> =
        combine(mainListPresentationMode, rootDirectory, imageDirectory, imageMap) {
            mode,
            rootDir,
            imageDir,
            currentImageMap,
            ->
            mode to
                UiMemoMappingInput(
                    memos = emptyList(),
                    rootDirectory = rootDir,
                    imageDirectory = imageDir,
                    imageMap = currentImageMap,
                    imageDependencySignature = "",
                    prioritizedMemoIds = emptySet(),
                )
        }.distinctUntilChanged { old, new ->
            old.first == new.first && old.second.hasSameUiDependencies(new.second)
        }.flatMapLatest { (mode, input) ->
            if (mode != MainListPresentationMode.PagedDefault) {
                flowOf(PagingData.empty())
            } else {
                Pager(
                    config =
                        PagingConfig(
                            pageSize = DEFAULT_MAIN_LIST_PAGE_SIZE,
                            initialLoadSize = DEFAULT_MAIN_LIST_INITIAL_LOAD_SIZE,
                            prefetchDistance = DEFAULT_MAIN_LIST_PREFETCH_DISTANCE,
                            enablePlaceholders = false,
                        ),
                    pagingSourceFactory = memoUiCoordinator.defaultMainListPagingSource,
                ).flow.map { pagingData ->
                    pagingData.map { memo ->
                        withContext(Dispatchers.Default) {
                            memoUiMapper.mapToUiModel(
                                memo = memo,
                                rootPath = input.rootDirectory,
                                imagePath = input.imageDirectory,
                                imageMap = input.imageMap,
                            )
                        }
                    }
                }
            }
        }.cachedIn(scope)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val uiMemos: StateFlow<List<MemoUiModel>> =
        memos.mapToUiModelState(
            rootDirectory = rootDirectory,
            imageDirectory = imageDirectory,
            imageMap = imageMap,
            memoUiMapper = memoUiMapper,
            scope = scope,
        )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val galleryUiMemos: StateFlow<List<MemoUiModel>> =
        memoUiCoordinator.galleryMemos().mapToUiModelState(
            rootDirectory = rootDirectory,
            imageDirectory = imageDirectory,
            imageMap = imageMap,
            memoUiMapper = memoUiMapper,
            scope = scope,
            transformMemos = { currentMemos ->
                currentMemos
                    .asSequence()
                    .filter { memo -> memo.imageUrls.any { path -> !isAudioAttachmentPath(path) } }
                    .sortedByDescending { memo -> memo.timestamp }
                    .toList()
            },
        )
}

private fun resolveMemoFlow(
    query: String,
    filter: MemoListFilter,
    memoUiCoordinator: MemoUiCoordinator,
    resolveMainMemoQueryUseCase: ResolveMainMemoQueryUseCase,
): Flow<List<Memo>> =
    when (val resolvedQuery = resolveMainMemoQueryUseCase(query = query)) {
        is ResolveMainMemoQueryUseCase.ResolvedQuery.BySearchText ->
            memoUiCoordinator.searchMemos(resolvedQuery.query)
        ResolveMainMemoQueryUseCase.ResolvedQuery.AllMemos ->
            memoUiCoordinator.memosByDateRange(filter.startDate, filter.endDate)
    }

private data class MemoQueryInput(
    val query: String,
    val filter: MemoListFilter,
)
