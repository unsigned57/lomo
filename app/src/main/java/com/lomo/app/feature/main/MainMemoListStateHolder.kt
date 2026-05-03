package com.lomo.app.feature.main

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.lomo.app.feature.common.MemoUiCoordinator
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.domain.model.MemoListFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val pagedUiMemos: Flow<PagingData<MemoUiModel>> =
        combine(mainMemoQueryInput, rootDirectory, imageDirectory, imageMap) {
            input,
            rootDir,
            imageDir,
            currentImageMap,
            ->
            input to
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
        }.flatMapLatest { (input, mappingInput) ->
            Pager(
                config =
                    PagingConfig(
                        pageSize = DEFAULT_MAIN_LIST_PAGE_SIZE,
                        initialLoadSize = DEFAULT_MAIN_LIST_INITIAL_LOAD_SIZE,
                        prefetchDistance = DEFAULT_MAIN_LIST_PREFETCH_DISTANCE,
                        enablePlaceholders = false,
                    ),
                pagingSourceFactory = {
                    memoUiCoordinator.mainListPagingSource(
                        input.query,
                        input.filter,
                    )
                },
            ).flow.map { pagingData ->
                pagingData.map { memo ->
                    withContext(Dispatchers.Default) {
                        memoUiMapper.mapToUiModel(
                            memo = memo,
                            rootPath = mappingInput.rootDirectory,
                            imagePath = mappingInput.imageDirectory,
                            imageMap = mappingInput.imageMap,
                        )
                    }
                }
            }
        }.cachedIn(scope)

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

private data class MemoQueryInput(
    val query: String,
    val filter: MemoListFilter,
)
