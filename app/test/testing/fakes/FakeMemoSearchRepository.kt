package com.lomo.app.testing.fakes

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.domain.model.Memo
import com.lomo.domain.model.TagSelection
import com.lomo.domain.model.TagSelectionMode
import com.lomo.domain.repository.MemoSearchRepository

class FakeMemoSearchRepository(
    private val store: FakeMemoStore,
) : MemoSearchRepository {
    override fun getMemosByTagPagingSource(selection: TagSelection): PagingSource<Int, Memo> =
        FakeMemoPagingSource { limit, offset ->
            store.taggedMemoPage(
                selection.path.value,
                selection.mode == TagSelectionMode.Subtree,
                limit,
                offset,
            )
        }
}

private class FakeMemoPagingSource(
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
