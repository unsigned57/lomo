package com.lomo.domain.usecase

import androidx.paging.PagingSource
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoTrashRepository

class MemoTrashUseCase(
    private val memoTrashRepository: MemoTrashRepository,
) {
    fun getDeletedMemosPagingSource(): PagingSource<Int, Memo> =
        memoTrashRepository.getDeletedMemosPagingSource()

    suspend fun restoreMemo(memo: Memo) {
        memoTrashRepository.restoreMemo(memo)
    }

    suspend fun deletePermanently(memo: Memo) {
        memoTrashRepository.deletePermanently(memo)
    }

    suspend fun clearTrash() {
        memoTrashRepository.clearTrash()
    }
}
