package com.lomo.app.feature.main

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.app.feature.common.memoPager
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.WorkspaceAuthority
import com.lomo.domain.usecase.MainMemoListQueryUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
private const val DEFAULT_MAIN_LIST_ENABLE_PLACEHOLDERS = true
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
    workspaceAuthority: StateFlow<WorkspaceAuthority?>,
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
            )
        }.distinctUntilChanged { old, new ->
            old.hasSameUiDependencies(new)
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val memoPagingData: StateFlow<PagingData<Memo>?> =
        combine(workspaceAuthority, mainMemoQueryInput) { authority, queryInput ->
            authority?.let { AuthorizedMemoQueryInput(authority = it, query = queryInput) }
        }.filterNotNull()
            .flatMapLatest { authorizedInput ->
                memoPager(
                    scope = scope,
                    pageSize = DEFAULT_MAIN_LIST_PAGE_SIZE,
                    initialLoadSize = DEFAULT_MAIN_LIST_INITIAL_LOAD_SIZE,
                    prefetchDistance = DEFAULT_MAIN_LIST_PREFETCH_DISTANCE,
                    enablePlaceholders = DEFAULT_MAIN_LIST_ENABLE_PLACEHOLDERS,
                    pagingSourceFactory = {
                        mainMemoListQueryUseCase.getMainListPagingSource(
                            authorizedInput.query.query,
                            authorizedInput.query.filter,
                        )
                    },
                )
            }.stateIn(scope, SharingStarted.Lazily, null)

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

    @OptIn(ExperimentalCoroutinesApi::class)
    val galleryPagedUiMemos: Flow<PagingData<MemoUiModel>> =
        combine(
            workspaceAuthority,
            rootDirectory,
            imageDirectory,
            imageMap,
        ) { authority, rootDir, imageDir, currentImageMap ->
            if (authority == null) {
                null
            } else {
                GalleryPagingInput(
                    rootDirectory = rootDir,
                    imageDirectory = imageDir,
                    imageMap = currentImageMap,
                )
            }
        }.filterNotNull()
            .flatMapLatest { input ->
                Pager(
                    PagingConfig(
                        pageSize = DEFAULT_MAIN_LIST_PAGE_SIZE,
                        initialLoadSize = DEFAULT_MAIN_LIST_INITIAL_LOAD_SIZE,
                        prefetchDistance = DEFAULT_MAIN_LIST_PREFETCH_DISTANCE,
                        enablePlaceholders = false,
                    ),
                ) { mainMemoListQueryUseCase.getGalleryMemosPagingSource() }.flow
                    .map { pagingData ->
                        pagingData
                            .filter { memo -> memo.imageUrls.any { path -> !isAudioAttachmentPath(path) } }
                            .map { memo ->
                                withContext(dispatcherProvider.default) {
                                    memoUiMapper.mapToUiModel(
                                        memo = memo,
                                        rootPath = input.rootDirectory,
                                        imagePath = input.imageDirectory,
                                        imageMap = input.imageMap,
                                    )
                                }
                            }
                    }
            }.cachedIn(scope)

}

private data class MemoQueryInput(
    val query: String,
    val filter: MemoListFilter,
)

private data class AuthorizedMemoQueryInput(
    val authority: WorkspaceAuthority,
    val query: MemoQueryInput,
)

private data class GalleryPagingInput(
    val rootDirectory: String?,
    val imageDirectory: String?,
    val imageMap: Map<String, android.net.Uri>,
)

private fun Map<String, android.net.Uri>.toPagingImageDependencySignature(): String =
    entries
        .asSequence()
        .sortedBy { (key, _) -> key }
        .joinToString(separator = "\n") { (key, uri) -> "$key=$uri" }
