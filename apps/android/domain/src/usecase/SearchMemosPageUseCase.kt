package com.lomo.domain.usecase

import androidx.paging.PagingSource
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoSearchMode
import com.lomo.domain.repository.MemoSearchRepository

class SearchMemosPageUseCase(
    private val repository: MemoSearchRepository,
) {
    fun getPagingSource(
        query: String,
        filter: MemoListFilter,
        mode: MemoSearchMode = MemoSearchMode.Fulltext,
    ): PagingSource<String, Memo> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return EmptyMemoPagingSource()
        }
        return repository.searchPagingSource(
            query = normalizedQuery,
            mode = mode,
            filter = filter,
        )
    }
}

private class EmptyMemoPagingSource : PagingSource<String, Memo>() {
    override suspend fun load(params: LoadParams<String>): LoadResult<String, Memo> =
        LoadResult.Page(
            data = emptyList(),
            prevKey = null,
            nextKey = null,
        )

    override fun getRefreshKey(state: androidx.paging.PagingState<String, Memo>): String? = null
}
