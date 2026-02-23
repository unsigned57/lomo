package com.lomo.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoTagCrossRefEntity
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.data.local.entity.toTagCrossRefs
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoDao {
    @Query("SELECT * FROM Lomo ORDER BY timestamp DESC, id DESC")
    fun getAllMemosFlow(): Flow<List<MemoEntity>>

    @Query("SELECT * FROM Lomo ORDER BY RANDOM() LIMIT :limit")
    suspend fun getRandomMemos(limit: Int): List<MemoEntity>

    @Query("SELECT * FROM Lomo ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMemos(limit: Int): List<MemoEntity>

    @Query("SELECT id FROM Lomo")
    suspend fun getAllMemoIds(): List<String>

    @Query("SELECT * FROM Lomo WHERE id IN (:ids)")
    suspend fun getMemosByIds(ids: List<String>): List<MemoEntity>

    @Query("SELECT COUNT(*) FROM Lomo")
    suspend fun getMemoCountSync(): Int

    @Query(
        """
        SELECT * FROM Lomo
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun getMemosPage(
        limit: Int,
        offset: Int,
    ): List<MemoEntity>

    // 保留原 LIKE 作为兜底或英文/符号搜索
    @Query(
        """
        SELECT * FROM Lomo 
        WHERE content LIKE '%' || :query || '%' 
        ORDER BY timestamp DESC, id DESC
        """,
    )
    fun searchMemosFlow(query: String): Flow<List<MemoEntity>>

    @Query(
        """
        SELECT Lomo.* FROM Lomo
        INNER JOIN lomo_fts ON lomo_fts.memoId = Lomo.id
        WHERE lomo_fts MATCH :matchQuery
        ORDER BY Lomo.timestamp DESC, Lomo.id DESC
        """,
    )
    fun searchMemosByFtsFlow(matchQuery: String): Flow<List<MemoEntity>>

    // FTS
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemoFts(fts: com.lomo.data.local.entity.MemoFtsEntity)

    @Query("DELETE FROM lomo_fts WHERE memoId = :memoId")
    suspend fun deleteMemoFts(memoId: String)

    @Query("DELETE FROM lomo_fts WHERE memoId IN (:memoIds)")
    suspend fun deleteMemoFtsByIds(memoIds: List<String>)

    @Query("DELETE FROM lomo_fts")
    suspend fun clearFts()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemos(memos: List<MemoEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemo(memo: MemoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTagRefs(refs: List<MemoTagCrossRefEntity>)

    @Query("DELETE FROM MemoTagCrossRef WHERE memoId = :memoId")
    suspend fun deleteTagRefsByMemoId(memoId: String)

    @Query("DELETE FROM MemoTagCrossRef WHERE memoId IN (:memoIds)")
    suspend fun deleteTagRefsByMemoIds(memoIds: List<String>)

    @Query("DELETE FROM MemoTagCrossRef")
    suspend fun clearTagRefs()

    suspend fun replaceTagRefsForMemo(memo: MemoEntity) {
        deleteTagRefsByMemoId(memo.id)
        val refs = memo.toTagCrossRefs()
        if (refs.isNotEmpty()) {
            insertTagRefs(refs)
        }
    }

    suspend fun replaceTagRefsForMemos(memos: List<MemoEntity>) {
        if (memos.isEmpty()) return
        deleteTagRefsByMemoIds(memos.map { it.id })
        val refs = memos.asSequence().flatMap { it.toTagCrossRefs().asSequence() }.toList()
        if (refs.isNotEmpty()) {
            insertTagRefs(refs)
        }
    }

    @Delete suspend fun deleteMemo(memo: MemoEntity) // Used for permanent delete

    @Query("DELETE FROM Lomo WHERE id = :id")
    suspend fun deleteMemoById(id: String)

    @Query("DELETE FROM Lomo WHERE id IN (:ids)")
    suspend fun deleteMemosByIds(ids: List<String>)

    @Query("DELETE FROM Lomo")
    suspend fun clearAll()

    @Query("DELETE FROM Lomo WHERE id NOT IN (:ids)")
    suspend fun deleteMemosNotIn(ids: List<String>)

    @Query("SELECT * FROM Lomo WHERE id = :id")
    suspend fun getMemo(id: String): MemoEntity?

    @Query("SELECT * FROM Lomo")
    suspend fun getAllMemosSync(): List<MemoEntity>

    @Query("SELECT * FROM Lomo WHERE date = :date")
    suspend fun getMemosByDate(date: String): List<MemoEntity>

    @Query("DELETE FROM Lomo WHERE date = :date")
    suspend fun deleteMemosByDate(date: String)

    // Tag Support (cross-ref table)
    @Query(
        """
        SELECT Lomo.*
        FROM Lomo
        INNER JOIN MemoTagCrossRef ON MemoTagCrossRef.memoId = Lomo.id
        WHERE MemoTagCrossRef.tag = :tag OR MemoTagCrossRef.tag LIKE :tagPrefix
        ORDER BY Lomo.timestamp DESC, Lomo.id DESC
    """,
    )
    fun getMemosByTagFlow(
        tag: String,
        tagPrefix: String,
    ): Flow<List<MemoEntity>>

    @Query("SELECT DISTINCT tag FROM MemoTagCrossRef ORDER BY tag COLLATE NOCASE")
    fun getAllTagsFlow(): Flow<List<String>>

    @Query(
        """
        SELECT tag AS name, COUNT(DISTINCT memoId) AS count
        FROM MemoTagCrossRef
        GROUP BY tag
        ORDER BY tag COLLATE NOCASE
        """,
    )
    fun getTagCountsFlow(): Flow<List<TagCountRow>>

    // Stats
    @Query("SELECT COUNT(*) FROM Lomo")
    fun getMemoCount(): Flow<Int>

    @Query("SELECT COUNT(DISTINCT date) FROM Lomo")
    fun getActiveDayCount(): Flow<Int>

    @Query("SELECT timestamp FROM Lomo ORDER BY timestamp DESC")
    fun getAllTimestamps(): Flow<List<Long>>

    @Query(
        "SELECT COUNT(*) FROM Lomo WHERE imageUrls LIKE '%' || :imagePath || '%' AND id != :excludeId",
    )
    suspend fun countMemosWithImage(
        imagePath: String,
        excludeId: String,
    ): Int

    // Trash Support
    @Query("SELECT * FROM LomoTrash ORDER BY timestamp DESC, id DESC")
    fun getDeletedMemosFlow(): Flow<List<TrashMemoEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrashMemos(memos: List<TrashMemoEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrashMemo(memo: TrashMemoEntity)

    @Query("SELECT * FROM LomoTrash WHERE id = :id")
    suspend fun getTrashMemo(id: String): TrashMemoEntity?

    @Query("SELECT * FROM LomoTrash WHERE date = :date")
    suspend fun getTrashMemosByDate(date: String): List<TrashMemoEntity>

    @Query("DELETE FROM LomoTrash WHERE id = :id")
    suspend fun deleteTrashMemoById(id: String)

    @Query("DELETE FROM LomoTrash WHERE id IN (:ids)")
    suspend fun deleteTrashMemosByIds(ids: List<String>)

    @Query("DELETE FROM LomoTrash WHERE date = :date")
    suspend fun deleteTrashMemosByDate(date: String)

    @Query("DELETE FROM LomoTrash")
    suspend fun clearTrash()
}

data class TagCountRow(
    val name: String,
    val count: Int,
)
