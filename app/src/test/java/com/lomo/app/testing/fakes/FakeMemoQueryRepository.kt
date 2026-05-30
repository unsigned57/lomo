package com.lomo.app.testing.fakes

import androidx.paging.PagingSource
import com.lomo.domain.model.DailyReviewCandidateBoundary
import com.lomo.domain.model.DailyReviewCandidateCursor
import com.lomo.domain.model.DailyReviewCandidatePage
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoQuerySpec
import com.lomo.domain.repository.MemoQueryRepository
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

class FakeMemoQueryRepository(
    private val store: FakeMemoStore,
) : MemoQueryRepository {
    override fun getAllMemosList(): Flow<List<Memo>> = store.observeAllActiveMemos()

    override fun getMemosByDateRange(
        startDate: LocalDate?,
        endDate: LocalDate?,
    ): Flow<List<Memo>> = store.observeActiveMemosInDateRange(startDate, endDate)

    override fun getGalleryMemosList(): Flow<List<Memo>> = store.observeGalleryActiveMemos()

    override suspend fun getRecentMemos(limit: Int): List<Memo> = store.recentActiveMemos(limit)

    override suspend fun getMemosPage(
        limit: Int,
        offset: Int,
    ): List<Memo> = store.activeMemoPage(limit, offset)

    override suspend fun getMemoCount(): Int = store.activeMemoCount()

    override suspend fun getDailyReviewCandidateBoundary(): DailyReviewCandidateBoundary? =
        store.captureDailyReviewCandidateBoundary()

    override suspend fun getDailyReviewCandidatePage(
        boundary: DailyReviewCandidateBoundary,
        cursor: DailyReviewCandidateCursor?,
        limit: Int,
    ): DailyReviewCandidatePage = store.dailyReviewCandidatePage(boundary, cursor, limit)

    override fun getMainListPagingSource(spec: MemoQuerySpec): PagingSource<Int, Memo> =
        store.mainListPagingSourceFor(spec)

    override fun getMainListCountFlow(spec: MemoQuerySpec): Flow<Int> = store.observeMainListCount(spec)

    override suspend fun getDefaultMainListIndexInWindow(
        id: String,
        limit: Int,
    ): Int? = store.defaultMainListIndexInWindow(id = id, limit = limit)

    override suspend fun getMemoById(id: String): Memo? = store.findActiveMemoById(id)

    override fun isSyncing(): Flow<Boolean> = store.observeSyncing()
}
