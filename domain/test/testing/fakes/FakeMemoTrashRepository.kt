package com.lomo.domain.testing.fakes

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoTrashRepository

class FakeMemoTrashRepository(
    private val store: FakeMemoStore,
) : MemoTrashRepository {
    override fun getDeletedMemosPagingSource(): PagingSource<Int, Memo> =
        FakeTrashMemoPagingSource { limit, offset -> store.deletedMemoPage(limit = limit, offset = offset) }

    override suspend fun restoreMemo(memo: Memo) = store.restoreDeletedMemo(memo)

    override suspend fun deletePermanently(memo: Memo) = store.removeDeletedMemoPermanently(memo)

    override suspend fun clearTrash() = store.removeAllDeletedMemos()
}

private class FakeTrashMemoPagingSource(
    private val pageLoader: suspend (limit: Int, offset: Int) -> List<Memo>,
) : PagingSource<Int, Memo>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Memo> {
        val offset = params.key ?: 0
        val items = pageLoader(params.loadSize, offset)
        return LoadResult.Page(
            data = items,
            prevKey = null,
            nextKey = if (items.size < params.loadSize) null else offset + items.size,
        )
    }

    override fun getRefreshKey(state: PagingState<Int, Memo>): Int? = state.anchorPosition
}
