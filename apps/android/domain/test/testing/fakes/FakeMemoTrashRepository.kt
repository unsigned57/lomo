package com.lomo.domain.testing.fakes

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoOperationId
import com.lomo.domain.repository.MemoTrashRepository

class FakeMemoTrashRepository(
    private val store: FakeMemoStore,
) : MemoTrashRepository {
    override fun getDeletedMemosPagingSource(): PagingSource<String, Memo> =
        FakeTrashMemoPagingSource { limit, offset -> store.deletedMemoPage(limit = limit, offset = offset) }

    override suspend fun restoreMemo(
        memo: Memo,
        operationId: MemoOperationId,
    ) = store.restoreDeletedMemo(memo)

    override suspend fun deletePermanently(
        memo: Memo,
        operationId: MemoOperationId,
    ) = store.removeDeletedMemoPermanently(memo)

    override suspend fun clearTrash(operationId: MemoOperationId) = store.removeAllDeletedMemos()
}

private class FakeTrashMemoPagingSource(
    private val pageLoader: suspend (limit: Int, offset: Int) -> List<Memo>,
) : PagingSource<String, Memo>() {
    override suspend fun load(params: LoadParams<String>): LoadResult<String, Memo> {
        val offset = decodeTrashCursor(params.key)
        val items = pageLoader(params.loadSize, offset)
        return LoadResult.Page(
            data = items,
            prevKey = null,
            nextKey = if (items.size < params.loadSize) null else encodeTrashCursor(offset + items.size),
        )
    }

    override fun getRefreshKey(state: PagingState<String, Memo>): String? = null
}

private fun encodeTrashCursor(offset: Int): String = "fake-trash-cursor:$offset"

private fun decodeTrashCursor(cursor: String?): Int =
    cursor?.removePrefix("fake-trash-cursor:")?.toIntOrNull()
        ?: 0
