package com.lomo.app.feature.common

import androidx.paging.PagingSource
import com.lomo.domain.model.Memo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val DEFAULT_MEMO_COLLECTION_PAGE_SIZE = 50

class MemoCollectionWindowStateHolder<Input>(
    sourceInput: Flow<Input>,
    private val source: (Input) -> PagingSource<Int, Memo>,
    private val scope: CoroutineScope,
    private val pageSize: Int = DEFAULT_MEMO_COLLECTION_PAGE_SIZE,
    private val onLoadError: (Throwable) -> Unit = {},
) {
    private val _memos = MutableStateFlow<List<Memo>>(emptyList())
    val memos: StateFlow<List<Memo>> = _memos.stateIn(scope, appWhileSubscribed(), emptyList())

    private val _canLoadMore = MutableStateFlow(false)
    val canLoadMore: StateFlow<Boolean> = _canLoadMore.stateIn(scope, appWhileSubscribed(), false)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.stateIn(scope, appWhileSubscribed(), false)

    private var currentInput: Input? = null
    private var currentSource: PagingSource<Int, Memo>? = null
    private var currentNextKey: Int? = null
    private var loadJob: Job? = null

    init {
        sourceInput
            .distinctUntilChanged()
            .onEach { input ->
                currentInput = input
                loadInitialPage(input = input)
            }.launchIn(scope)
    }

    fun loadNextPage() {
        val source = currentSource ?: return
        val nextKey = currentNextKey ?: return
        loadJob?.cancel()
        loadJob =
            scope.launch {
                _isLoading.value = true
                when (
                    val result =
                        source.load(
                            PagingSource.LoadParams.Append(
                                key = nextKey,
                                loadSize = pageSize,
                                placeholdersEnabled = false,
                            ),
                        )
                ) {
                    is PagingSource.LoadResult.Page -> {
                        _memos.value = _memos.value + result.data
                        currentNextKey = result.nextKey
                        _canLoadMore.value = result.nextKey != null
                    }
                    is PagingSource.LoadResult.Error -> onLoadError(result.throwable)
                    is PagingSource.LoadResult.Invalid -> reloadCurrent(source = source)
                }
                _isLoading.value = false
            }
    }

    private fun loadInitialPage(
        input: Input,
    ) {
        loadJob?.cancel()
        currentSource = source(input)
        _memos.value = emptyList()
        _canLoadMore.value = false
        loadJob =
            scope.launch {
                _isLoading.value = true
                when (
                    val result =
                        requireNotNull(currentSource).load(
                            PagingSource.LoadParams.Refresh(
                                key = 0,
                                loadSize = pageSize,
                                placeholdersEnabled = false,
                            ),
                        )
                ) {
                    is PagingSource.LoadResult.Page -> {
                        _memos.value = result.data
                        currentNextKey = result.nextKey
                        _canLoadMore.value = result.nextKey != null
                    }
                    is PagingSource.LoadResult.Error -> onLoadError(result.throwable)
                    is PagingSource.LoadResult.Invalid -> reloadCurrent(source = requireNotNull(currentSource))
                }
                _isLoading.value = false
            }
    }

    private fun reloadCurrent(
        source: PagingSource<Int, Memo>,
    ) {
        val input = currentInput ?: return
        if (currentSource === source) {
            loadInitialPage(input = input)
        }
    }
}

fun MemoCollectionWindowStateHolder(
    source: () -> PagingSource<Int, Memo>,
    scope: CoroutineScope,
    pageSize: Int = DEFAULT_MEMO_COLLECTION_PAGE_SIZE,
    onLoadError: (Throwable) -> Unit = {},
): MemoCollectionWindowStateHolder<Unit> =
    MemoCollectionWindowStateHolder(
        sourceInput = flowOf(Unit),
        source = { source() },
        scope = scope,
        pageSize = pageSize,
        onLoadError = onLoadError,
    )
