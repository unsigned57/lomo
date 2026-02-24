package com.lomo.domain.repository

import com.lomo.domain.model.Memo
import kotlinx.coroutines.flow.Flow

/**
 * Core repository for memo data operations: CRUD, search, trash, stats, and sync.
 */
interface MemoRepository {
    // Data operations
    fun getAllMemosList(): Flow<List<Memo>>

    suspend fun refreshMemos()

    fun isSyncing(): Flow<Boolean>

    suspend fun saveMemo(
        content: String,
        timestamp: Long = System.currentTimeMillis(),
    )

    suspend fun updateMemo(
        memo: Memo,
        newContent: String,
    )

    suspend fun deleteMemo(memo: Memo)

    // Search & Filter
    fun searchMemosList(query: String): Flow<List<Memo>>

    fun getMemosByTagList(tag: String): Flow<List<Memo>>

    fun getActiveDayCount(): Flow<Int>

    // Trash
    fun getDeletedMemosList(): Flow<List<Memo>>

    suspend fun restoreMemo(memo: Memo)

    suspend fun deletePermanently(memo: Memo)
}
