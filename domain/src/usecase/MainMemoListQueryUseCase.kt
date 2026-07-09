package com.lomo.domain.usecase

import androidx.paging.PagingSource
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoQuerySpec
import com.lomo.domain.repository.MainListQueryRepository
import com.lomo.domain.repository.MemoListQueryRepository
import kotlinx.coroutines.flow.Flow

class MainMemoListQueryUseCase(
    private val mainListQueryRepository: MainListQueryRepository,
    private val memoListQueryRepository: MemoListQueryRepository,
) {
    fun getMainListPagingSource(
        query: String,
        filter: MemoListFilter,
    ): PagingSource<Int, Memo> =
        mainListQueryRepository.getMainListPagingSource(
            spec = MemoQuerySpec.fromFilter(queryText = query, filter = filter),
        )

    fun getMainListCountFlow(
        query: String,
        filter: MemoListFilter,
    ): Flow<Int> =
        mainListQueryRepository.getMainListCountFlow(
            spec = MemoQuerySpec.fromFilter(queryText = query, filter = filter),
        )

    fun getGalleryMemosList(): Flow<List<Memo>> = memoListQueryRepository.getGalleryMemosList()

    suspend fun getDefaultMainListIndexInWindow(
        id: String,
        limit: Int,
    ): Int? =
        mainListQueryRepository.getDefaultMainListIndexInWindow(
            id = id,
            limit = limit,
        )

    suspend fun getMemoById(id: String): Memo? = mainListQueryRepository.getMemoById(id)

    fun isSyncing(): Flow<Boolean> = mainListQueryRepository.isSyncing()
}
