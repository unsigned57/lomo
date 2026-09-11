package com.lomo.app.testing.fakes

import androidx.paging.PagingSource
import com.lomo.domain.model.DailyReviewCandidateBoundary
import com.lomo.domain.model.DailyReviewCandidateCursor
import com.lomo.domain.model.DailyReviewCandidatePage
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoQuerySpec
import com.lomo.domain.repository.MemoQueryRepository
import kotlinx.coroutines.flow.Flow

class FakeMemoQueryRepository(
    private val store: FakeMemoStore,
) : MemoQueryRepository {
    override fun getGalleryMemosPagingSource(): PagingSource<String, Memo> =
        store.galleryPagingSource()

    override suspend fun getRecentMemos(limit: Int): List<Memo> = store.recentActiveMemos(limit)

    override suspend fun getMemoCount(): Int = store.activeMemoCount()

    override suspend fun getDailyReviewCandidateBoundary(): DailyReviewCandidateBoundary? =
        store.captureDailyReviewCandidateBoundary()

    override suspend fun getDailyReviewCandidatePage(
        boundary: DailyReviewCandidateBoundary,
        cursor: DailyReviewCandidateCursor?,
        limit: Int,
    ): DailyReviewCandidatePage = store.dailyReviewCandidatePage(boundary, cursor, limit)

    override suspend fun getDailyReviewVisibleUnseenPage(
        cursor: DailyReviewCandidateCursor?,
        limit: Int,
    ): DailyReviewCandidatePage = store.dailyReviewVisibleUnseenPage(cursor, limit)

    override fun getMainListPagingSource(spec: MemoQuerySpec): PagingSource<String, Memo> =
        store.mainListPagingSourceFor(spec)

    override suspend fun getDefaultMainListIndexInWindow(
        id: String,
        limit: Int,
    ): Int? = store.defaultMainListIndexInWindow(id = id, limit = limit)

    override suspend fun getMemoById(id: String): Memo? = store.findActiveMemoById(id)

    override fun isSyncing(): Flow<Boolean> = store.observeSyncing()
}
