package com.lomo.domain.repository

import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoTagCount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Core repository for memo data operations: CRUD, search, trash, stats, and sync.
 */
interface MemoRepository {
    // Data operations
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

    /**
     * Returns total memo count as a one-shot value.
     *
     * Default implementation adapts from [getMemoCountFlow] for compatibility.
     */
    suspend fun getMemoCount(): Int = getMemoCountFlow().first()

    suspend fun refreshMemos()

    fun isSyncing(): Flow<Boolean>

    suspend fun saveMemo(
        content: String,
        timestamp: Long,
    )

    suspend fun updateMemo(
        memo: Memo,
        newContent: String,
    )

    suspend fun deleteMemo(memo: Memo)

    // Search & Filter
    fun searchMemosList(query: String): Flow<List<Memo>>

    fun getMemosByTagList(tag: String): Flow<List<Memo>>

    fun getMemoCountFlow(): Flow<Int>

    fun getMemoTimestampsFlow(): Flow<List<Long>>

    fun getMemoCountByDateFlow(): Flow<Map<String, Int>>

    fun getTagCountsFlow(): Flow<List<MemoTagCount>>

    fun getActiveDayCount(): Flow<Int>

    // Trash
    fun getDeletedMemosList(): Flow<List<Memo>>

    suspend fun restoreMemo(memo: Memo)

    suspend fun deletePermanently(memo: Memo)
}
