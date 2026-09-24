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
    ): PagingSource<String, Memo> =
        mainListQueryRepository.getMainListPagingSource(
            spec = MemoQuerySpec.fromFilter(queryText = query, filter = filter),
        )

    fun getGalleryMemosPagingSource(): PagingSource<String, Memo> =
        memoListQueryRepository.getGalleryMemosPagingSource()

    suspend fun rankInDefaultMainList(id: String): Int? =
        mainListQueryRepository.rankInDefaultMainList(id)

    suspend fun rankInMainListQuery(
        spec: MemoQuerySpec,
        id: String,
    ): Int? = mainListQueryRepository.rankInMainListQuery(spec, id)

    fun reanchorMainListToIdentity(id: String) {
        mainListQueryRepository.reanchorMainListToIdentity(id)
    }

    suspend fun getMemoById(id: String): Memo? = mainListQueryRepository.getMemoById(id)

    fun isSyncing(): Flow<Boolean> = mainListQueryRepository.isSyncing()
}
