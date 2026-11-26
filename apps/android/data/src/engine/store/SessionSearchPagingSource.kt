package com.lomo.data.engine.store

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.data.engine.SessionNativeBridge
import com.lomo.data.engine.withEngineFailureConversion
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoContentKind
import com.lomo.domain.model.MemoSearchMode
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import com.lomo.nativebridge.SessionSearchMode
import com.lomo.nativebridge.SessionSearchOutcome
import com.lomo.nativebridge.SessionSearchRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import com.lomo.nativebridge.StorePageCursor as BridgePageCursor

private const val SESSION_SEARCH_MAX_PAGE_SIZE = 256

/**
 * Paging3 source over unified Tantivy session search ([MemoSearchMode.Fulltext] and [MemoSearchMode.Fuzzy]).
 *
 * Each hit contains the complete bounded summary. Refresh keys are centered memo identities resolved
 * via `anchor`, while Append and Prepend keys are exclusive publication-coupled cursors. A stale
 * query epoch is [LoadResult.Invalid] so Paging3 rebuilds from the factory instead of mixing two
 * search generations.
 */
internal class SessionSearchPagingSource(
    private val session: SessionNativeBridge,
    private val mode: MemoSearchMode,
    private val filters: StoreMemoFilters,
    private val sort: StoreMemoSort = StoreMemoSort(),
    private val queryEpoch: ULong,
    private val text: String,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    private val previewPrefetcher: (suspend (List<Memo>) -> List<Memo>)? = null,
    private val isEpochCurrent: () -> Boolean = { true },
    registerInvalidation: ((PagingSource<*, *>) -> Unit)? = null,
) : PagingSource<String, Memo>() {
    init {
        registerInvalidation?.invoke(this)
    }

    override suspend fun load(params: LoadParams<String>): LoadResult<String, Memo> =
        withContext(dispatcherProvider.io) {
            try {
                val (cursor, anchor, backward) =
                    when (params) {
                        is LoadParams.Refresh ->
                            Triple(null, params.key?.takeUnless(String::isBlank), false)

                        is LoadParams.Append ->
                            Triple(
                                BridgePageCursor(requirePagingCursor(params.key, "append")),
                                null,
                                false,
                            )

                        is LoadParams.Prepend ->
                            Triple(
                                BridgePageCursor(requirePagingCursor(params.key, "prepend")),
                                null,
                                true,
                            )
                    }
                val pageSize = params.loadSize.coerceIn(1, SESSION_SEARCH_MAX_PAGE_SIZE)
                val outcome =
                    withEngineFailureConversion {
                        session.sessionSearch(
                            SessionSearchRequest(
                                filters = filters.toNativeFilters(),
                                sort = sort.toNativeSort(),
                                queryEpoch = queryEpoch,
                                mode =
                                when (mode) {
                                    MemoSearchMode.Fulltext -> SessionSearchMode.FULLTEXT
                                    MemoSearchMode.Fuzzy -> SessionSearchMode.FUZZY
                                },
                                text = text,
                                cursor = cursor,
                                anchor = anchor,
                                backward = backward,
                                pageSize = pageSize.toUInt(),
                            ),
                        )
                    }
                when (outcome) {
                    is SessionSearchOutcome.Discarded -> LoadResult.Invalid()

                    is SessionSearchOutcome.Ready -> {
                        val rawMemos =
                            outcome.page.items.map { hit ->
                                val summary = hit.summary.toStoreSummary()
                                summary.toDomainMemo(summary.bodyPreview, MemoContentKind.Preview)
                            }
                        val memos = previewPrefetcher?.invoke(rawMemos) ?: rawMemos
                        if (!isEpochCurrent()) {
                            LoadResult.Invalid()
                        } else {
                            val itemsBefore = outcome.page.itemsBefore.toSearchPlaceholderCount("items_before")
                            val itemsAfter = outcome.page.itemsAfter.toSearchPlaceholderCount("items_after")
                            LoadResult.Page(
                                data = memos,
                                prevKey = outcome.page.prevCursor?.encoded,
                                nextKey = outcome.page.nextCursor?.encoded,
                                itemsBefore =
                                when (params) {
                                    is LoadParams.Append -> LoadResult.Page.COUNT_UNDEFINED
                                    else -> itemsBefore
                                },
                                itemsAfter =
                                when (params) {
                                    is LoadParams.Prepend -> LoadResult.Page.COUNT_UNDEFINED
                                    else -> itemsAfter
                                },
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LoadResult.Error(error)
            }
        }

    override fun getRefreshKey(state: PagingState<String, Memo>): String? {
        val anchor = state.anchorPosition ?: return null
        val refreshLoadSize = state.config.initialLoadSize.coerceAtLeast(state.config.pageSize)
        val startPosition = (anchor - refreshLoadSize / 2).coerceAtLeast(0)
        if (startPosition == 0) {
            return null
        }
        return state.closestItemToPosition(startPosition)?.id
    }
}

private fun ULong.toSearchPlaceholderCount(field: String): Int {
    require(this <= Int.MAX_VALUE.toULong()) {
        "$field $this does not fit paging placeholder count"
    }
    return toInt()
}
