package com.lomo.data.local.dao

import androidx.paging.PagingSource
import androidx.room.*
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoTagCrossRef
import com.lomo.data.local.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoDao {
    @Query("SELECT * FROM Lomo WHERE isDeleted = 0 ORDER BY timestamp DESC")
    fun getAllMemos(): PagingSource<Int, MemoEntity>

    @Query("SELECT * FROM Lomo WHERE isDeleted = 0 ORDER BY RANDOM() LIMIT :limit")
    suspend fun getRandomMemos(limit: Int): List<MemoEntity>

    @Query("SELECT * FROM Lomo WHERE isDeleted = 0 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMemos(limit: Int): List<MemoEntity>

    @Query("SELECT id FROM Lomo WHERE isDeleted = 0")
    suspend fun getAllMemoIds(): List<String>

    @Query("SELECT * FROM Lomo WHERE id IN (:ids)")
    suspend fun getMemosByIds(ids: List<String>): List<MemoEntity>

    @Query(
        """
        SELECT * FROM Lomo 
        WHERE content LIKE '%' || :query || '%' 
        AND isDeleted = 0 
        ORDER BY timestamp DESC
        """,
    )
    fun searchMemos(query: String): PagingSource<Int, MemoEntity>

    // FTS
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemoFts(fts: com.lomo.data.local.entity.MemoFtsEntity)

    @Query("DELETE FROM lomo_fts WHERE memoId = :memoId")
    suspend fun deleteMemoFts(memoId: String)

    @Query("DELETE FROM lomo_fts")
    suspend fun clearFts()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemos(memos: List<MemoEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemo(memo: MemoEntity)

    @Delete suspend fun deleteMemo(memo: MemoEntity) // Used for permanent delete

    @Query("UPDATE Lomo SET isDeleted = 1 WHERE id = :id")
    suspend fun softDeleteMemo(id: String)

    @Query("SELECT * FROM Lomo WHERE isDeleted = 1 ORDER BY timestamp DESC")
    fun getDeletedMemos(): PagingSource<Int, MemoEntity>

    @Query("DELETE FROM Lomo")
    suspend fun clearAll()

    @Query("DELETE FROM Lomo WHERE id NOT IN (:ids)")
    suspend fun deleteMemosNotIn(ids: List<String>)

    @Query("SELECT * FROM Lomo WHERE id = :id")
    suspend fun getMemo(id: String): MemoEntity?

    @Query("SELECT * FROM Lomo")
    suspend fun getAllMemosSync(): List<MemoEntity>

    @Query("SELECT * FROM Lomo WHERE date = :date AND isDeleted = :isDeleted")
    suspend fun getMemosByDate(
        date: String,
        isDeleted: Boolean,
    ): List<MemoEntity>

    // Tag Support
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTags(tags: List<TagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemoTagCrossRefs(refs: List<MemoTagCrossRef>)

    @Query("DELETE FROM memo_tag_cross_ref WHERE memoId = :memoId")
    suspend fun deleteMemoTags(memoId: String)

    @Query(
        """
        SELECT Lomo.* FROM Lomo 
        INNER JOIN memo_tag_cross_ref ON Lomo.id = memo_tag_cross_ref.memoId 
        WHERE (memo_tag_cross_ref.tagName = :tag OR memo_tag_cross_ref.tagName LIKE :tag || '/%') 
        AND Lomo.isDeleted = 0
        ORDER BY Lomo.timestamp DESC
    """,
    )
    fun getMemosByTag(tag: String): PagingSource<Int, MemoEntity>

    @Query("SELECT * FROM tags")
    fun getAllTags(): Flow<List<TagEntity>>

    // Stats
    @Query("SELECT COUNT(*) FROM Lomo WHERE isDeleted = 0")
    fun getMemoCount(): Flow<Int>

    @Query("SELECT timestamp FROM Lomo WHERE isDeleted = 0 ORDER BY timestamp DESC")
    fun getAllTimestamps(): Flow<List<Long>>

    @Query(
        "SELECT name, (SELECT COUNT(*) FROM memo_tag_cross_ref INNER JOIN Lomo ON memo_tag_cross_ref.memoId = Lomo.id WHERE tagName = name AND Lomo.isDeleted = 0) as count FROM tags",
    )
    fun getTagCounts(): Flow<List<com.lomo.domain.model.TagCount>>

    @Query(
        "SELECT COUNT(*) FROM Lomo WHERE imageUrls LIKE '%' || :imagePath || '%' AND id != :excludeId",
    )
    suspend fun countMemosWithImage(
        imagePath: String,
        excludeId: String,
    ): Int

    // Cleanup orphan tags (tags with no associated non-deleted memos)
    @Query(
        """
            DELETE FROM tags WHERE name NOT IN (
                SELECT DISTINCT tagName FROM memo_tag_cross_ref 
                INNER JOIN Lomo ON memo_tag_cross_ref.memoId = Lomo.id 
                WHERE Lomo.isDeleted = 0
            )
        """,
    )
    suspend fun deleteOrphanTags()

    @Query("DELETE FROM tags")
    suspend fun clearTags()

    @Query("DELETE FROM memo_tag_cross_ref")
    suspend fun clearCrossRefs()
}
