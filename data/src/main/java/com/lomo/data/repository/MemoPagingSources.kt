package com.lomo.data.repository

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.data.local.dao.DefaultMainListMemoRow
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.data.local.projection.MemoProjectionProjector
import com.lomo.domain.model.Memo

internal class MemoRowMappingPagingSource(
    private val source: PagingSource<Int, DefaultMainListMemoRow>,
) : PagingSource<Int, Memo>() {
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
                    data = result.data.map(DefaultMainListMemoRow::toDomain),
                    prevKey = result.prevKey,
                    nextKey = result.nextKey,
                    itemsBefore = result.itemsBefore,
                    itemsAfter = result.itemsAfter,
                )
        }

    override fun getRefreshKey(state: PagingState<Int, Memo>): Int? =
        source.getRefreshKey(state.toDefaultMainListRowState())
}

internal class TrashMemoMappingPagingSource(
    private val source: PagingSource<Int, TrashMemoEntity>,
) : PagingSource<Int, Memo>() {
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
                    data = result.data.map(TrashMemoEntity::toDomain),
                    prevKey = result.prevKey,
                    nextKey = result.nextKey,
                    itemsBefore = result.itemsBefore,
                    itemsAfter = result.itemsAfter,
                )
        }

    override fun getRefreshKey(state: PagingState<Int, Memo>): Int? =
        source.getRefreshKey(state.toTrashMemoState())
}

internal fun DefaultMainListMemoRow.toDomain(): Memo = memo.toDomain(isPinned = isPinned)

private fun PagingState<Int, Memo>.toDefaultMainListRowState(): PagingState<Int, DefaultMainListMemoRow> =
    PagingState(
        pages =
            pages.map { page ->
                PagingSource.LoadResult.Page(
                    data =
                        page.data.map { memo ->
                            DefaultMainListMemoRow(
                                memo = MemoProjectionProjector.projectActive(memo).entity,
                                isPinned = memo.isPinned,
                            )
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

private fun PagingState<Int, Memo>.toTrashMemoState(): PagingState<Int, TrashMemoEntity> =
    PagingState(
        pages =
            pages.map { page ->
                PagingSource.LoadResult.Page(
                    data = page.data.map { memo -> MemoProjectionProjector.projectTrash(memo).entity },
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
