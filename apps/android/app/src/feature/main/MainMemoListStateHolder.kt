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
import com.lomo.domain.model.WorkspaceMount
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
import kotlinx.coroutines.withContext

private const val SEARCH_DEBOUNCE_MILLIS = 150L
internal const val DEFAULT_MAIN_LIST_PAGE_SIZE = 20
private const val DEFAULT_MAIN_LIST_INITIAL_LOAD_SIZE = DEFAULT_MAIN_LIST_PAGE_SIZE * 3
private const val DEFAULT_MAIN_LIST_PREFETCH_DISTANCE = 10
private const val DEFAULT_MAIN_LIST_ENABLE_PLACEHOLDERS = true

sealed interface GalleryUiMemosState {
    data object Loading : GalleryUiMemosState

    data class Loaded(
        val memos: List<MemoUiModel>,
    ) : GalleryUiMemosState
}

/** Inputs for the main memo list state holder: the query sources plus the current media mapping. */
internal data class MainMemoListStateHolderDependencies(
    val scope: CoroutineScope,
    val mainMemoListQueryUseCase: MainMemoListQueryUseCase,
    val memoUiMapper: MemoUiMapper,
    val searchQuery: StateFlow<String>,
    val memoListFilter: StateFlow<MemoListFilter>,
    val mount: StateFlow<WorkspaceMount>,
    val rootDirectory: StateFlow<String?>,
    val imageDirectory: StateFlow<String?>,
    val imageMap: StateFlow<Map<String, android.net.Uri>>,
    val dispatcherProvider: com.lomo.domain.usecase.DispatcherProvider,
)

internal class MainMemoListStateHolder(
    dependencies: MainMemoListStateHolderDependencies,
) {
    private val scope = dependencies.scope
    private val mainMemoListQueryUseCase = dependencies.mainMemoListQueryUseCase
    private val memoUiMapper = dependencies.memoUiMapper
    private val searchQuery = dependencies.searchQuery
    private val memoListFilter = dependencies.memoListFilter
    private val mount = dependencies.mount
    private val rootDirectory = dependencies.rootDirectory
    private val imageDirectory = dependencies.imageDirectory
    private val imageMap = dependencies.imageMap
    private val dispatcherProvider = dependencies.dispatcherProvider
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
            )
        }.distinctUntilChanged { old, new ->
            old.hasSameUiDependencies(new)
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    // behavior-contract: uncached-paging-ok: private intermediate; public surfaces use cachedIn
    private val memoPagingData: StateFlow<PagingData<Memo>?> =
        combine(mount, mainMemoQueryInput) { session, queryInput ->
            session.admittedAuthority?.let { active ->
                AuthorizedMemoQueryInput(authority = active, query = queryInput)
            }
        }.filterNotNull()
            .distinctUntilChanged()
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
            }.stateIn(scope, appWhileSubscribed(), null)

    val pagedUiMemos: Flow<PagingData<MemoUiModel>> =
        combine(
            mappingInput,
            memoPagingData.filterNotNull(),
        ) { currentMappingInput, pagingData ->
            pagingData.map { memo ->
                withContext(dispatcherProvider.default) {
                    memoUiMapper.mapToCachedUiModel(
                        memo = memo,
                        rootPath = currentMappingInput.rootDirectory,
                        imagePath = currentMappingInput.imageDirectory,
                        imageMap = currentMappingInput.imageMap,
                        reminders = memo.reminders,
                    )
                }
            }
        }.cachedIn(scope)

    @OptIn(ExperimentalCoroutinesApi::class)
    val galleryPagedUiMemos: Flow<PagingData<MemoUiModel>> =
        combine(
            mount,
            rootDirectory,
            imageDirectory,
            imageMap,
        ) { session, rootDir, imageDir, currentImageMap ->
            if (session.admittedAuthority == null) {
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
                                    memoUiMapper.mapToCachedUiModel(
                                        memo = memo,
                                        rootPath = input.rootDirectory,
                                        imagePath = input.imageDirectory,
                                        imageMap = input.imageMap,
                                        reminders = memo.reminders,
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
