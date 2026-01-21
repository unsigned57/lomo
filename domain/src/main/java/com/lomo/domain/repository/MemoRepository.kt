package com.lomo.domain.repository

import androidx.paging.PagingData
import com.lomo.domain.model.Memo
import com.lomo.domain.model.TagCount
import kotlinx.coroutines.flow.Flow

interface MemoRepository {
    // Info about the source directory
    suspend fun setRootDirectory(path: String)
    suspend fun setImageDirectory(path: String)
    fun getRootDirectory(): Flow<String?>
    suspend fun getRootDirectoryOnce(): String? // New: synchronous initial read
    fun getRootDisplayName(): Flow<String?> // New
    fun getImageDirectory(): Flow<String?>
    fun getImageDisplayName(): Flow<String?> // New
    suspend fun updateRootUri(uri: String?)
    suspend fun updateImageUri(uri: String?)
    
    // Voice
    suspend fun setVoiceDirectory(path: String)
    fun getVoiceDirectory(): Flow<String?>
    fun getVoiceDisplayName(): Flow<String?>
    suspend fun updateVoiceUri(uri: String?)
    
    suspend fun createDefaultImageDirectory(): String?
    suspend fun createDefaultVoiceDirectory(): String?

    // Data operations
    fun getAllMemos(): Flow<PagingData<Memo>>
    suspend fun getRandomMemos(limit: Int): List<Memo>
    suspend fun getDailyReviewMemos(limit: Int, seedDate: java.time.LocalDate): List<Memo>
    suspend fun refreshMemos() // Trigger file scan
    fun isSyncing(): Flow<Boolean>

    suspend fun saveMemo(content: String, timestamp: Long = System.currentTimeMillis())
    suspend fun updateMemo(memo: Memo, newContent: String)
    // suspend fun updateLines(memo: Memo, lineIndex: Int, checked: Boolean) - Removed
    suspend fun deleteMemo(memo: Memo)

    // Search
    // Search & Filter
    fun searchMemos(query: String): Flow<PagingData<Memo>>
    fun getMemosByTag(tag: String): Flow<PagingData<Memo>>

    // Stats
    fun getMemoCount(): Flow<Int>
    fun getAllTimestamps(): Flow<List<Long>>
    fun getTagCounts(): Flow<List<TagCount>>
    fun getAllTags(): Flow<List<String>>

    // Trash
    fun getDeletedMemos(): Flow<PagingData<Memo>>
    suspend fun restoreMemo(memo: Memo)
    suspend fun deletePermanently(memo: Memo)

    // Images & Voice
    suspend fun saveImage(uri: android.net.Uri): String
    suspend fun createVoiceFile(filename: String): android.net.Uri
    suspend fun deleteVoiceFile(filename: String)
    fun getImageUriMap(): Flow<Map<String, String>>
    suspend fun syncImageCache()

    // Settings
    fun getDateFormat(): Flow<String>
    suspend fun setDateFormat(format: String)
    fun getTimeFormat(): Flow<String>
    suspend fun setTimeFormat(format: String)

    fun getThemeMode(): Flow<String>
    suspend fun setThemeMode(mode: String)

    // Storage Formats
    fun getStorageFilenameFormat(): Flow<String>
    suspend fun setStorageFilenameFormat(format: String)
    fun getStorageTimestampFormat(): Flow<String>
    suspend fun setStorageTimestampFormat(format: String)

    fun isHapticFeedbackEnabled(): Flow<Boolean>
    suspend fun setHapticFeedbackEnabled(enabled: Boolean)

    fun isCheckUpdatesOnStartupEnabled(): Flow<Boolean>
    suspend fun setCheckUpdatesOnStartup(enabled: Boolean)
}
