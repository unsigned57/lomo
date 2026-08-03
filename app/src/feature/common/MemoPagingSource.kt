package com.lomo.app.feature.common

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

private const val DEFAULT_PAGE_SIZE = 20
private const val DEFAULT_INITIAL_LOAD_SIZE = 60
private const val DEFAULT_PREFETCH_DISTANCE = 10
private const val DEFAULT_ENABLE_PLACEHOLDERS = true

fun <Key : Any, Value : Any> memoPager(
    scope: CoroutineScope,
    pageSize: Int = DEFAULT_PAGE_SIZE,
    initialLoadSize: Int = DEFAULT_INITIAL_LOAD_SIZE,
    prefetchDistance: Int = DEFAULT_PREFETCH_DISTANCE,
    enablePlaceholders: Boolean = DEFAULT_ENABLE_PLACEHOLDERS,
    pagingSourceFactory: () -> PagingSource<Key, Value>,
): Flow<PagingData<Value>> =
    Pager(
        config = memoPagingConfig(
            pageSize = pageSize,
            initialLoadSize = initialLoadSize,
            prefetchDistance = prefetchDistance,
            enablePlaceholders = enablePlaceholders,
        ),
        pagingSourceFactory = pagingSourceFactory,
    ).flow.cachedIn(scope)

internal fun memoPagingConfig(
    pageSize: Int = DEFAULT_PAGE_SIZE,
    initialLoadSize: Int = DEFAULT_INITIAL_LOAD_SIZE,
    prefetchDistance: Int = DEFAULT_PREFETCH_DISTANCE,
    enablePlaceholders: Boolean = DEFAULT_ENABLE_PLACEHOLDERS,
): PagingConfig =
    PagingConfig(
        pageSize = pageSize,
        initialLoadSize = initialLoadSize,
        prefetchDistance = prefetchDistance,
        enablePlaceholders = enablePlaceholders,
        jumpThreshold = Int.MIN_VALUE,
    )

class LoadingAwarePagingSource<Key : Any, Value : Any>(
    private val delegate: PagingSource<Key, Value>,
    private val onLoadingChanged: ((Boolean) -> Unit)? = null,
    private val onError: ((Throwable) -> Unit)? = null,
) : PagingSource<Key, Value>() {

    init {
        delegate.registerInvalidatedCallback {
            invalidate()
        }
    }

    override val jumpingSupported: Boolean
        get() = delegate.jumpingSupported

    override val keyReuseSupported: Boolean
        get() = delegate.keyReuseSupported

    override fun getRefreshKey(state: PagingState<Key, Value>): Key? = delegate.getRefreshKey(state)

    override suspend fun load(params: LoadParams<Key>): LoadResult<Key, Value> {
        try {
            if (params is LoadParams.Refresh) {
                onLoadingChanged?.invoke(true)
            }
            val result = delegate.load(params)
            if (result is LoadResult.Error) {
                onError?.invoke(result.throwable)
            }
            return result
        } finally {
            if (params is LoadParams.Refresh) {
                onLoadingChanged?.invoke(false)
            }
        }
    }
}
