package com.lomo.data.engine.store

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.data.engine.SessionNativeBridge
import com.lomo.data.engine.withEngineFailureConversion
import com.lomo.domain.model.Memo
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import com.lomo.nativebridge.SessionSearchMode
import com.lomo.nativebridge.SessionSearchOutcome
import com.lomo.nativebridge.SessionSearchRequest
import com.lomo.nativebridge.StorePageCursor as BridgePageCursor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

private const val SESSION_SEARCH_MAX_PAGE_SIZE = 256

/**
 * Paging3 source over application-session fuzzy search.
 *
 * Each hit contains the complete bounded summary. A stale query epoch is [LoadResult.Invalid] so
 * Paging3 rebuilds from the factory instead of mixing two search generations.
 */
internal class SessionSearchPagingSource(
    private val session: SessionNativeBridge,
    private val filters: StoreMemoFilters,
    private val queryEpoch: ULong,
    private val text: String,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    registerInvalidation: ((PagingSource<*, *>) -> Unit)? = null,
) : PagingSource<String, Memo>() {
    init {
        registerInvalidation?.invoke(this)
    }

    override suspend fun load(params: LoadParams<String>): LoadResult<String, Memo> =
        withContext(dispatcherProvider.io) {
            try {
                // Refresh keys are viewport identities, not page cursors; a forward-only
                // search page rejects prepend instead of reinterpreting the key.
                val cursor =
                    when (params) {
                        is LoadParams.Refresh -> null
                        is LoadParams.Append -> BridgePageCursor(params.key)
                        is LoadParams.Prepend ->
                            return@withContext LoadResult.Error(
                                IllegalStateException("session search paging is forward-only"),
                            )
                    }
                val pageSize = params.loadSize.coerceIn(1, SESSION_SEARCH_MAX_PAGE_SIZE)
                val outcome =
                    withEngineFailureConversion {
                        session.sessionSearch(
                            SessionSearchRequest(
                                queryEpoch = queryEpoch,
                                filters = filters.toNativeFilters(),
                                mode = SessionSearchMode.FUZZY,
                                text = text,
                                cursor = cursor,
                                pageSize = pageSize.toUInt(),
                            ),
                        )
                    }
                when (outcome) {
                    is SessionSearchOutcome.Discarded -> LoadResult.Invalid()
                    is SessionSearchOutcome.Ready -> {
                        val memos =
                            outcome.page.items.map { hit ->
                                val summary = hit.summary.toStoreSummary()
                                summary.toDomainMemo(summary.bodyPreview, com.lomo.domain.model.MemoContentKind.Preview)
                            }
                        LoadResult.Page(
                            data = memos,
                            prevKey = null,
                            nextKey = outcome.page.nextCursor?.encoded,
                        )
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                LoadResult.Error(error)
            }
        }

    override fun getRefreshKey(state: PagingState<String, Memo>): String? = null
}
