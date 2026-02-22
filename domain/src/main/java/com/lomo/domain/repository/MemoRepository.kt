package com.lomo.domain.repository

import androidx.paging.PagingData
import com.lomo.domain.model.Memo
import com.lomo.domain.model.TagCount
import kotlinx.coroutines.flow.Flow

/**
 * Core repository for memo data operations: CRUD, search, trash, stats, and sync.
 * Directory read methods are kept here so CRUD ViewModels don't need to depend on
 * [SettingsRepository]. Directory write methods live in [SettingsRepository].
 * Media file operations live in [MediaRepository].
 */
interface MemoRepository {
    // Directory reads
    fun getRootDirectory(): Flow<String?>

    suspend fun getRootDirectoryOnce(): String?

    fun getRootDisplayName(): Flow<String?>

    fun getImageDirectory(): Flow<String?>

    fun getImageDisplayName(): Flow<String?>

    fun getVoiceDirectory(): Flow<String?>

    fun getVoiceDisplayName(): Flow<String?>

    // Data operations
    fun getAllMemos(): Flow<PagingData<Memo>>

    suspend fun getRandomMemos(limit: Int): List<Memo>

    suspend fun getDailyReviewMemos(
        limit: Int,
        seedDate: java.time.LocalDate,
    ): List<Memo>

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
    fun searchMemos(query: String): Flow<PagingData<Memo>>

    fun getMemosByTag(tag: String): Flow<PagingData<Memo>>

    // Stats
    fun getMemoCount(): Flow<Int>

    fun getActiveDayCount(): Flow<Int>

    fun getAllTimestamps(): Flow<List<Long>>

    fun getTagCounts(): Flow<List<TagCount>>

    fun getAllTags(): Flow<List<String>>

    // Trash
    fun getDeletedMemos(): Flow<PagingData<Memo>>

    suspend fun restoreMemo(memo: Memo)

    suspend fun deletePermanently(memo: Memo)
}
