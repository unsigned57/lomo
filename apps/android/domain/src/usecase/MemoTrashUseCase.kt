package com.lomo.domain.usecase

import androidx.paging.PagingSource
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoOperationId
import com.lomo.domain.repository.MemoTrashRepository

class MemoTrashUseCase(
    private val memoTrashRepository: MemoTrashRepository,
) {
    fun getDeletedMemosPagingSource(): PagingSource<String, Memo> =
        memoTrashRepository.getDeletedMemosPagingSource()

    suspend fun restoreMemo(
        memo: Memo,
        operationId: MemoOperationId,
    ) {
        memoTrashRepository.restoreMemo(memo, operationId)
    }

    suspend fun deletePermanently(
        memo: Memo,
        operationId: MemoOperationId,
    ) {
        memoTrashRepository.deletePermanently(memo, operationId)
    }

    suspend fun clearTrash(operationId: MemoOperationId) {
        memoTrashRepository.clearTrash(operationId)
    }
}
