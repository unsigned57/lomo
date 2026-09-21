package com.lomo.domain.repository

import androidx.paging.PagingSource
import com.lomo.domain.model.DailyReviewCandidateBoundary
import com.lomo.domain.model.DailyReviewCandidateCursor
import com.lomo.domain.model.DailyReviewCandidatePage
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoOperationId
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoQuerySpec
import com.lomo.domain.model.MemoSearchMode
import com.lomo.domain.model.TagSelection
import com.lomo.domain.model.MemoStatistics
import com.lomo.domain.model.MemoTask
import com.lomo.domain.model.MemoTagCount
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZoneId

/**
 * Read-side list access that can serve bounded memo pages without full-list fallbacks.
 */
interface MemoListQueryRepository {
    /** Returns a Rust-cursor-backed source; each load transfers one bounded gallery page. */
    fun getGalleryMemosPagingSource(): PagingSource<String, Memo>

    suspend fun getRecentMemos(limit: Int): List<Memo>

    suspend fun getMemoCount(): Int

    /**
     * Emits when the derived list projection accepts a new publication. Non-paging observers
     * collect this instead of commanding a refresh from a mutation site.
     */
    fun observeListProjection(): Flow<Unit>
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

    /**
     * Reads the current visible ordering behind the captured boundary with the same opaque
     * continuation semantics. This is used only to fill ids that disappeared or arrived after a
     * session snapshot; it never exposes an integer offset to callers.
     */
    suspend fun getDailyReviewVisibleUnseenPage(
        cursor: DailyReviewCandidateCursor?,
        limit: Int,
    ): DailyReviewCandidatePage
}

interface MainListQueryRepository {
    fun getMainListPagingSource(spec: MemoQuerySpec): PagingSource<String, Memo>

    /**
     * Returns the zero-based rank of [id] in the repository's default main-list ordering.
     * Missing or filtered-out identities return null; they must not be reported as head.
     */
    suspend fun rankInDefaultMainList(id: String): Int?

    /**
     * Reanchors the live main-list paging source so the next refresh starts at [id].
     * No-op when no main-list source is registered.
     */
    fun reanchorMainListToIdentity(id: String)

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

    /** Commits Rust-parsed facts from a document command without rebuilding the workspace. */
    suspend fun commitDocumentMutation(
        mutation: com.lomo.domain.model.MemoDocumentMutation,
    )

    suspend fun saveMemo(
        attempt: com.lomo.domain.model.MemoCreateAttempt,
    ): Memo

    suspend fun updateMemo(
        attempt: com.lomo.domain.model.MemoUpdateAttempt,
    )

    suspend fun deleteMemo(
        memo: Memo,
        operationId: MemoOperationId,
    )

    suspend fun restoreMemoRevision(
        currentMemo: Memo,
        revision: MemoRevision,
        operationId: MemoOperationId,
    )

    suspend fun setMemoPinned(
        memoId: String,
        pinned: Boolean,
        operationId: MemoOperationId,
    )
}

interface MemoSearchRepository {
    fun getMemosByTagPagingSource(selection: TagSelection): PagingSource<String, Memo>

    fun searchPagingSource(
        query: String,
        mode: MemoSearchMode,
        filter: MemoListFilter,
    ): PagingSource<String, Memo>
}

interface MemoStatisticsRepository {
    suspend fun getMemoStatistics(
        zone: ZoneId,
        today: LocalDate,
    ): MemoStatistics

    fun observeMemoStatistics(
        zone: ZoneId,
        today: LocalDate,
    ): Flow<MemoStatistics>

    fun getMemoCountFlow(): Flow<Int>

    fun getSidebarStatisticsFlow(): Flow<com.lomo.domain.model.MemoSidebarStatistics>

    fun getMemoCountByDateFlow(): Flow<Map<String, Int>>

    fun getTagCountsFlow(): Flow<List<MemoTagCount>>

    fun getActiveDayCount(): Flow<Int>
}

interface MemoTrashRepository {
    fun getDeletedMemosPagingSource(): PagingSource<String, Memo>

    suspend fun restoreMemo(
        memo: Memo,
        operationId: MemoOperationId,
    )

    suspend fun deletePermanently(
        memo: Memo,
        operationId: MemoOperationId,
    )

    suspend fun clearTrash(operationId: MemoOperationId)
}

interface MemoTaskRepository {
    suspend fun listTasks(): List<MemoTask>

    suspend fun toggleTask(
        task: MemoTask,
        done: Boolean,
    )
}
