package com.lomo.data.repository

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.data.local.dao.DefaultMainListMemoRow
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.domain.model.Memo
import java.util.concurrent.ConcurrentHashMap

internal class MemoRowMappingPagingSource(
    private val source: PagingSource<Int, DefaultMainListMemoRow>,
) : PagingSource<Int, Memo>() {
    private val sourceRowsByMemoId = ConcurrentHashMap<String, DefaultMainListMemoRow>()
    init {
        source.registerInvalidatedCallback(::invalidate)
    }

    override val jumpingSupported: Boolean
        get() = source.jumpingSupported

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Memo> =
        when (val result = source.load(params)) {
            is LoadResult.Error -> LoadResult.Error(result.throwable)
            is LoadResult.Invalid -> LoadResult.Invalid()
            is LoadResult.Page ->
                LoadResult.Page(
                    data =
                        result.data.map { row ->
                            sourceRowsByMemoId[row.memo.id] = row
                            row.toDomain()
                        },
                    prevKey = result.prevKey,
                    nextKey = result.nextKey,
                    itemsBefore = result.itemsBefore,
                    itemsAfter = result.itemsAfter,
                )
        }

    override fun getRefreshKey(state: PagingState<Int, Memo>): Int? =
        source.getRefreshKey(state.toDefaultMainListRowState(sourceRowsByMemoId))
}

internal class TrashMemoMappingPagingSource(
    private val source: PagingSource<Int, TrashMemoEntity>,
) : PagingSource<Int, Memo>() {
    private val sourceRowsByMemoId = ConcurrentHashMap<String, TrashMemoEntity>()
    init {
        source.registerInvalidatedCallback(::invalidate)
    }

    override val jumpingSupported: Boolean
        get() = source.jumpingSupported

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Memo> =
        when (val result = source.load(params)) {
            is LoadResult.Error -> LoadResult.Error(result.throwable)
            is LoadResult.Invalid -> LoadResult.Invalid()
            is LoadResult.Page ->
                LoadResult.Page(
                    data =
                        result.data.map { row ->
                            sourceRowsByMemoId[row.id] = row
                            row.toDomain()
                        },
                    prevKey = result.prevKey,
                    nextKey = result.nextKey,
                    itemsBefore = result.itemsBefore,
                    itemsAfter = result.itemsAfter,
                )
        }

    override fun getRefreshKey(state: PagingState<Int, Memo>): Int? =
        source.getRefreshKey(state.toTrashMemoState(sourceRowsByMemoId))
}

internal fun DefaultMainListMemoRow.toDomain(): Memo = memo.toDomain(isPinned = isPinned)

private fun PagingState<Int, Memo>.toDefaultMainListRowState(
    sourceRowsByMemoId: Map<String, DefaultMainListMemoRow>,
): PagingState<Int, DefaultMainListMemoRow> =
    PagingState(
        pages =
            pages.map { page ->
                PagingSource.LoadResult.Page(
                    data =
                        page.data.map { memo ->
                            checkNotNull(sourceRowsByMemoId[memo.id]) {
                                "Paging refresh state contains a memo that was not emitted by the source: ${memo.id}"
                            }
                        },
                    prevKey = page.prevKey,
                    nextKey = page.nextKey,
                    itemsBefore = page.itemsBefore,
                    itemsAfter = page.itemsAfter,
                )
            },
        anchorPosition = anchorPosition,
        config = config,
        leadingPlaceholderCount = 0,
    )

private fun PagingState<Int, Memo>.toTrashMemoState(
    sourceRowsByMemoId: Map<String, TrashMemoEntity>,
): PagingState<Int, TrashMemoEntity> =
    PagingState(
        pages =
            pages.map { page ->
                PagingSource.LoadResult.Page(
                    data =
                        page.data.map { memo ->
                            checkNotNull(sourceRowsByMemoId[memo.id]) {
                                "Trash paging refresh state contains a memo that was not emitted by the source: ${memo.id}"
                            }
                        },
                    prevKey = page.prevKey,
                    nextKey = page.nextKey,
                    itemsBefore = page.itemsBefore,
                    itemsAfter = page.itemsAfter,
                )
            },
        anchorPosition = anchorPosition,
        config = config,
        leadingPlaceholderCount = 0,
    )
