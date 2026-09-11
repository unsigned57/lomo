package com.lomo.app.testing.fakes

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
    val searchPagingCalls = mutableListOf<SearchPagingCall>()

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
    ): PagingSource<String, Memo> {
        searchPagingCalls += SearchPagingCall(query = query, mode = mode, filter = filter)
        return store.mainListPagingSourceFor(MemoQuerySpec.fromFilter(queryText = query, filter = filter))
    }

    data class SearchPagingCall(
        val query: String,
        val mode: MemoSearchMode,
        val filter: MemoListFilter,
    )
}

private class FakeMemoPagingSource(
    private val pageLoader: suspend (limit: Int, offset: Int) -> List<Memo>,
) : PagingSource<String, Memo>() {
    override suspend fun load(params: LoadParams<String>): LoadResult<String, Memo> {
        val offset = decodeTagCursor(params.key)
        val items = pageLoader(params.loadSize, offset)
        return LoadResult.Page(
            data = items,
            prevKey = null,
            nextKey = if (items.size < params.loadSize) null else encodeTagCursor(offset + items.size),
        )
    }

    override fun getRefreshKey(state: PagingState<String, Memo>): String? = null
}

private fun encodeTagCursor(offset: Int): String = "fake-tag-cursor:$offset"

private fun decodeTagCursor(cursor: String?): Int =
    cursor?.removePrefix("fake-tag-cursor:")?.toIntOrNull()?.coerceAtLeast(0)
        ?: 0
