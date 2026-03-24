package com.lomo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoTagCrossRefEntity
import com.lomo.data.local.entity.toTagCrossRefs

@Dao
interface MemoTagDao {
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
}
