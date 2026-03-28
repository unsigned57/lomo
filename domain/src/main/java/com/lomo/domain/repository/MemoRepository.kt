package com.lomo.domain.repository

import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoTagCount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Core repository for memo data operations: CRUD, search, trash, stats, and sync.
 */
interface MemoQueryRepository {
    fun getAllMemosList(): Flow<List<Memo>>

    suspend fun getRecentMemos(limit: Int): List<Memo>

    /**
     * Returns a stable page from the default memo ordering used by [getAllMemosList].
     *
     * Default implementation preserves backward compatibility for repositories that only expose
     * flow-based list observation, but concrete implementations should override for efficiency.
     */
    suspend fun getMemosPage(
        limit: Int,
        offset: Int,
    ): List<Memo> =
        if (limit <= 0 || offset < 0) {
            emptyList()
        } else {
            getAllMemosList().first().drop(offset).take(limit)
        }

    suspend fun getMemoCount(): Int

    /**
     * Returns one memo by id without forcing callers to reload the whole list.
     *
     * Default implementation preserves compatibility for repositories that only expose
     * flow-based list observation, but concrete implementations should override for efficiency.
     */
    suspend fun getMemoById(id: String): Memo? =
        getAllMemosList().first().firstOrNull { memo -> memo.id == id }

    fun isSyncing(): Flow<Boolean>
}

interface MemoMutationRepository {
    suspend fun refreshMemos()

    suspend fun saveMemo(
        content: String,
        timestamp: Long,
    )

    suspend fun updateMemo(
        memo: Memo,
        newContent: String,
    )

    suspend fun deleteMemo(memo: Memo)

    suspend fun setMemoPinned(
        memoId: String,
        pinned: Boolean,
    )
}

interface MemoSearchRepository {
    fun searchMemosList(query: String): Flow<List<Memo>>

    fun getMemosByTagList(tag: String): Flow<List<Memo>>

    fun getMemoCountFlow(): Flow<Int>

    fun getMemoTimestampsFlow(): Flow<List<Long>>

    fun getMemoCountByDateFlow(): Flow<Map<String, Int>>

    fun getTagCountsFlow(): Flow<List<MemoTagCount>>

    fun getActiveDayCount(): Flow<Int>
}

interface MemoTrashRepository {
    fun getDeletedMemosList(): Flow<List<Memo>>

    suspend fun restoreMemo(memo: Memo)

    suspend fun deletePermanently(memo: Memo)

    suspend fun clearTrash()
}

interface MemoRepository :
    MemoQueryRepository,
    MemoMutationRepository,
    MemoSearchRepository,
    MemoTrashRepository
