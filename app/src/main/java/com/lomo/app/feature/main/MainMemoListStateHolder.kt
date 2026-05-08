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
private const val DEFAULT_MAIN_LIST_PAGE_SIZE = 20
private const val DEFAULT_MAIN_LIST_INITIAL_LOAD_SIZE = DEFAULT_MAIN_LIST_PAGE_SIZE
private const val DEFAULT_MAIN_LIST_PREFETCH_DISTANCE = 10
private const val DEFAULT_MAIN_LIST_DIRECT_JUMP_THRESHOLD = DEFAULT_MAIN_LIST_PAGE_SIZE * 3

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

    private val mappingInput: StateFlow<UiMemoMappingInput> =
        combine(rootDirectory, imageDirectory, imageMap) {
            rootDir,
            imageDir,
            currentImageMap,
            ->
            UiMemoMappingInput(
                memos = emptyList(),
                rootDirectory = rootDir,
                imageDirectory = imageDir,
                imageMap = currentImageMap,
                imageDependencySignature = currentImageMap.toPagingImageDependencySignature(),
                prioritizedMemoIds = emptySet(),
            )
        }.distinctUntilChanged { old, new ->
            old.hasSameUiDependencies(new)
        }.stateIn(
            scope,
            appWhileSubscribed(),
            UiMemoMappingInput(
                memos = emptyList(),
                rootDirectory = null,
                imageDirectory = null,
                imageMap = emptyMap(),
                imageDependencySignature = "",
                prioritizedMemoIds = emptySet(),
            ),
        )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val memoPagingData: Flow<PagingData<Memo>> =
        mainMemoQueryInput
            .flatMapLatest { queryInput ->
                Pager(
                    config =
                        PagingConfig(
                            pageSize = DEFAULT_MAIN_LIST_PAGE_SIZE,
                            initialLoadSize = DEFAULT_MAIN_LIST_INITIAL_LOAD_SIZE,
                            prefetchDistance = DEFAULT_MAIN_LIST_PREFETCH_DISTANCE,
                            enablePlaceholders = true,
                            jumpThreshold = DEFAULT_MAIN_LIST_DIRECT_JUMP_THRESHOLD,
                        ),
                    pagingSourceFactory = {
                        memoUiCoordinator.mainListPagingSource(
                            queryInput.query,
                            queryInput.filter,
                        )
                    },
                ).flow
            }.cachedIn(scope)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val mainListTotalCount: StateFlow<Int> =
        mainMemoQueryInput
            .flatMapLatest { queryInput ->
                memoUiCoordinator.mainListCount(queryInput.query, queryInput.filter)
            }.distinctUntilChanged()
            .stateIn(scope, appWhileSubscribed(), 0)

    val pagedUiMemos: Flow<PagingData<MemoUiModel>> =
        combine(memoPagingData, mappingInput) {
            pagingData,
            currentMappingInput,
            ->
            pagingData.map { memo ->
                withContext(Dispatchers.Default) {
                    memoUiMapper.mapToUiModel(
                        memo = memo,
                        rootPath = currentMappingInput.rootDirectory,
                        imagePath = currentMappingInput.imageDirectory,
                        imageMap = currentMappingInput.imageMap,
                    )
                }
            }
        }

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

private fun Map<String, android.net.Uri>.toPagingImageDependencySignature(): String =
    entries
        .asSequence()
        .sortedBy { (key, _) -> key }
        .joinToString(separator = "\n") { (key, uri) -> "$key=$uri" }
