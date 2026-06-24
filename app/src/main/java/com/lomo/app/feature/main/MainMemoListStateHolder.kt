package com.lomo.app.feature.main

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.app.feature.common.memoPager
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.usecase.MainMemoListQueryUseCase
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.withContext

private const val SEARCH_DEBOUNCE_MILLIS = 150L
internal const val DEFAULT_MAIN_LIST_PAGE_SIZE = 20
private const val DEFAULT_MAIN_LIST_INITIAL_LOAD_SIZE = DEFAULT_MAIN_LIST_PAGE_SIZE * 3
private const val DEFAULT_MAIN_LIST_PREFETCH_DISTANCE = 10
private const val DEFAULT_MAIN_LIST_ENABLE_PLACEHOLDERS = false
internal const val DEFAULT_MAIN_LIST_DIRECT_FOCUS_WINDOW_LIMIT = DEFAULT_MAIN_LIST_PAGE_SIZE * 3

sealed interface GalleryUiMemosState {
    data object Loading : GalleryUiMemosState

    data class Loaded(
        val memos: List<MemoUiModel>,
    ) : GalleryUiMemosState
}

internal class MainMemoListStateHolder(
    scope: CoroutineScope,
    mainMemoListQueryUseCase: MainMemoListQueryUseCase,
    memoUiMapper: MemoUiMapper,
    searchQuery: StateFlow<String>,
    memoListFilter: StateFlow<MemoListFilter>,
    rootDirectory: StateFlow<String?>,
    imageDirectory: StateFlow<String?>,
    imageMap: StateFlow<Map<String, android.net.Uri>>,
    dispatcherProvider: com.lomo.domain.usecase.DispatcherProvider,
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

    private val mappingInput: Flow<UiMemoMappingInput> =
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
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val memoPagingData: StateFlow<PagingData<Memo>?> =
        mainMemoQueryInput
            .flatMapLatest { queryInput ->
                memoPager(
                    scope = scope,
                    pageSize = DEFAULT_MAIN_LIST_PAGE_SIZE,
                    initialLoadSize = DEFAULT_MAIN_LIST_INITIAL_LOAD_SIZE,
                    prefetchDistance = DEFAULT_MAIN_LIST_PREFETCH_DISTANCE,
                    enablePlaceholders = DEFAULT_MAIN_LIST_ENABLE_PLACEHOLDERS,
                    jumpThreshold = DEFAULT_MAIN_LIST_DIRECT_FOCUS_WINDOW_LIMIT,
                    pagingSourceFactory = {
                        mainMemoListQueryUseCase.getMainListPagingSource(
                            queryInput.query,
                            queryInput.filter,
                        )
                    },
                )
            }.stateIn(scope, SharingStarted.Lazily, null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val mainListTotalCount: StateFlow<Int> =
        mainMemoQueryInput
            .flatMapLatest { queryInput ->
                mainMemoListQueryUseCase.getMainListCountFlow(queryInput.query, queryInput.filter)
            }.distinctUntilChanged()
            .stateIn(scope, appWhileSubscribed(), 0)

    val pagedUiMemos: Flow<PagingData<MemoUiModel>> =
        combine(
            mappingInput,
            memoPagingData.filterNotNull(),
        ) { currentMappingInput, pagingData ->
            pagingData.map { memo ->
                withContext(dispatcherProvider.default) {
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
    val galleryUiMemosState: StateFlow<GalleryUiMemosState> =
        mainMemoListQueryUseCase.getGalleryMemosList().mapToUiModels(
            rootDirectory = rootDirectory,
            imageDirectory = imageDirectory,
            imageMap = imageMap,
            memoUiMapper = memoUiMapper,
            transformMemos = { currentMemos ->
                currentMemos
                    .asSequence()
                    .filter { memo -> memo.imageUrls.any { path -> !isAudioAttachmentPath(path) } }
                    .sortedByDescending { memo -> memo.timestamp }
                    .toList()
            },
        ).map { uiMemos ->
            GalleryUiMemosState.Loaded(uiMemos.toImmutableList())
        }.stateIn(scope, appWhileSubscribed(), GalleryUiMemosState.Loading)

    val galleryUiMemos: StateFlow<List<MemoUiModel>> =
        galleryUiMemosState
            .map { state ->
                when (state) {
                    GalleryUiMemosState.Loading -> emptyList()
                    is GalleryUiMemosState.Loaded -> state.memos
                }
            }.stateIn(scope, appWhileSubscribed(), emptyList())
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
