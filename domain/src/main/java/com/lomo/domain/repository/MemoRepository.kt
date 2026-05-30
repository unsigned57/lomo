package com.lomo.domain.repository

import androidx.paging.PagingSource
import com.lomo.domain.model.DailyReviewCandidateBoundary
import com.lomo.domain.model.DailyReviewCandidateCursor
import com.lomo.domain.model.DailyReviewCandidatePage
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoQuerySpec
import com.lomo.domain.model.MemoStatistics
import com.lomo.domain.model.MemoTagCount
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZoneId

/**
 * Read-side list access that can serve bounded memo pages without full-list fallbacks.
 */
interface MemoListQueryRepository {
    fun getAllMemosList(): Flow<List<Memo>>

    fun getMemosByDateRange(
        startDate: LocalDate?,
        endDate: LocalDate?,
    ): Flow<List<Memo>>

    fun getGalleryMemosList(): Flow<List<Memo>>

    suspend fun getRecentMemos(limit: Int): List<Memo>

    /**
     * Returns a stable page from the default memo ordering used by [getAllMemosList].
     */
    suspend fun getMemosPage(
        limit: Int,
        offset: Int,
    ): List<Memo>

    suspend fun getMemoCount(): Int
}

interface DailyReviewCandidateRepository {
    /**
     * Captures the stable high-water boundary for a Daily Review candidate session in the default
     * main-list ordering. Implementations must make later candidate pages exclude rows that sort
     * ahead of this boundary, even if the backing collection changes after the boundary is captured.
     */
    suspend fun getDailyReviewCandidateBoundary(): DailyReviewCandidateBoundary?

    /**
     * Returns candidate ids in default main-list order at or behind [boundary], starting after
     * [cursor]. [cursor] is null for the first page. Implementations may encode repository-owned
     * snapshot tokens in [DailyReviewCandidateBoundary.token] and [DailyReviewCandidateCursor.token].
     */
    suspend fun getDailyReviewCandidatePage(
        boundary: DailyReviewCandidateBoundary,
        cursor: DailyReviewCandidateCursor?,
        limit: Int,
    ): DailyReviewCandidatePage
}

interface MainListQueryRepository {
    fun getMainListPagingSource(spec: MemoQuerySpec): PagingSource<Int, Memo>

    fun getMainListCountFlow(spec: MemoQuerySpec): Flow<Int>

    /**
     * Returns the zero-based index of a memo only when it is inside a bounded head window of the
     * repository's default main-list ordering. This is an explicit focus/navigation policy, not an
     * exact whole-list position contract.
     */
    suspend fun getDefaultMainListIndexInWindow(
        id: String,
        limit: Int,
    ): Int?

    /**
     * Returns one memo by id without forcing callers to reload the whole list.
     */
    suspend fun getMemoById(id: String): Memo?

    fun isSyncing(): Flow<Boolean>
}

interface MemoQueryRepository :
    MemoListQueryRepository,
    DailyReviewCandidateRepository,
    MainListQueryRepository

interface MemoMutationRepository {
    suspend fun refreshMemos()

    suspend fun saveMemo(
        content: String,
        timestamp: Long,
        geoLocation: String? = null,
    )

    suspend fun updateMemo(
        memo: Memo,
        newContent: String,
    )

    suspend fun deleteMemo(memo: Memo)

    suspend fun restoreMemoRevision(
        currentMemo: Memo,
        revisionId: String,
    )

    suspend fun setMemoPinned(
        memoId: String,
        pinned: Boolean,
    )
}

interface MemoSearchRepository {
    fun getMemosByTagPagingSource(tag: String): PagingSource<Int, Memo>
}

interface MemoStatisticsRepository {
    suspend fun getMemoStatistics(
        zone: ZoneId,
        today: LocalDate,
    ): MemoStatistics

    fun getMemoCountFlow(): Flow<Int>

    fun getMemoTimestampsFlow(): Flow<List<Long>>

    fun getMemoCountByDateFlow(): Flow<Map<String, Int>>

    fun getTagCountsFlow(): Flow<List<MemoTagCount>>

    fun getActiveDayCount(): Flow<Int>
}

interface MemoTrashRepository {
    fun getDeletedMemosPagingSource(): PagingSource<Int, Memo>

    suspend fun restoreMemo(memo: Memo)

    suspend fun deletePermanently(memo: Memo)

    suspend fun clearTrash()
}
