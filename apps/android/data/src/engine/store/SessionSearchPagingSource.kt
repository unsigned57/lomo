package com.lomo.data.engine.store

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.data.engine.SessionNativeBridge
import com.lomo.data.engine.withEngineFailureConversion
import com.lomo.domain.model.Memo
import com.lomo.nativebridge.SessionSearchMode
import com.lomo.nativebridge.SessionSearchOutcome
import com.lomo.nativebridge.SessionSearchRequest
import com.lomo.nativebridge.StorePageCursor as BridgePageCursor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SESSION_SEARCH_MAX_PAGE_SIZE = 256

/**
 * Paging3 source over application-session fuzzy search.
 *
 * Hits are hydrated from the store projection. A stale query epoch is [LoadResult.Invalid] so
 * Paging3 rebuilds from the factory instead of mixing two search generations.
 */
internal class SessionSearchPagingSource(
    private val session: SessionNativeBridge,
    private val port: StorePort,
    private val queryEpoch: ULong,
    private val text: String,
    registerInvalidation: ((PagingSource<*, *>) -> Unit)? = null,
) : PagingSource<String, Memo>() {
    init {
        registerInvalidation?.invoke(this)
    }

    override suspend fun load(params: LoadParams<String>): LoadResult<String, Memo> =
        withContext(Dispatchers.IO) {
            try {
                val pageSize = params.loadSize.coerceIn(1, SESSION_SEARCH_MAX_PAGE_SIZE)
                val outcome =
                    withEngineFailureConversion {
                        session.sessionSearch(
                            SessionSearchRequest(
                                queryEpoch = queryEpoch,
                                mode = SessionSearchMode.FUZZY,
                                text = text,
                                cursor = params.key?.let { encoded -> BridgePageCursor(encoded) },
                                pageSize = pageSize.toUInt(),
                            ),
                        )
                    }
                when (outcome) {
                    is SessionSearchOutcome.Discarded -> LoadResult.Invalid()
                    is SessionSearchOutcome.Ready -> {
                        val memos =
                            outcome.page.items.mapNotNull { hit ->
                                // behavior-contract: loop-io-ok: search page is bounded; no bulk getMemo API
                                port.getMemo(hit.memoId)?.toDomainMemo()
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
