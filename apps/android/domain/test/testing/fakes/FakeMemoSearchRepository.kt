package com.lomo.domain.testing.fakes

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoQuerySpec
import com.lomo.domain.model.MemoSearchMode
import com.lomo.domain.model.TagSelection
import com.lomo.domain.model.TagSelectionMode
import com.lomo.domain.repository.MemoSearchRepository

class FakeMemoSearchRepository(
    private val store: FakeMemoStore,
) : MemoSearchRepository {
    override fun getMemosByTagPagingSource(selection: TagSelection): PagingSource<String, Memo> =
        FakeMemoPagingSource { limit, offset ->
            store.taggedMemoPage(
                selection.path.value,
                selection.mode == TagSelectionMode.Subtree,
                limit,
                offset,
            )
        }

    override fun searchPagingSource(
        query: String,
        mode: MemoSearchMode,
        filter: MemoListFilter,
    ): PagingSource<String, Memo> =
        store.mainListPagingSourceFor(MemoQuerySpec.fromFilter(queryText = query, filter = filter))
}

private class FakeMemoPagingSource(
    private val pageLoader: suspend (limit: Int, offset: Int) -> List<Memo>,
) : PagingSource<String, Memo>() {
    override suspend fun load(params: LoadParams<String>): LoadResult<String, Memo> {
        val offset = decodeCursor(params.key)
        val items = pageLoader(params.loadSize, offset)
        return LoadResult.Page(
            data = items,
            prevKey = null,
            nextKey = if (items.size < params.loadSize) null else encodeCursor(offset + items.size),
        )
    }

    override fun getRefreshKey(state: PagingState<String, Memo>): String? = null
}

private fun encodeCursor(offset: Int): String = "fake-tag-cursor:$offset"

private fun decodeCursor(cursor: String?): Int =
    cursor?.removePrefix("fake-tag-cursor:")?.toIntOrNull()
        ?: 0
