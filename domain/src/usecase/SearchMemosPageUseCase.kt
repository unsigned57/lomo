package com.lomo.domain.usecase

import androidx.paging.PagingSource
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoQuerySpec
import com.lomo.domain.repository.MainListQueryRepository

class SearchMemosPageUseCase(
    private val repository: MainListQueryRepository,
) {
    fun getPagingSource(
        query: String,
        filter: MemoListFilter,
    ): PagingSource<Int, Memo> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return EmptyMemoPagingSource()
        }
        return repository.getMainListPagingSource(
            spec = MemoQuerySpec.fromFilter(queryText = normalizedQuery, filter = filter),
        )
    }
}

private class EmptyMemoPagingSource : PagingSource<Int, Memo>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Memo> =
        LoadResult.Page(
            data = emptyList(),
            prevKey = null,
            nextKey = null,
        )

    override fun getRefreshKey(state: androidx.paging.PagingState<Int, Memo>): Int? = null
}
