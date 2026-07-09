package com.lomo.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.lomo.data.local.entity.MemoTagCrossRefEntity
import com.lomo.data.local.projection.ActiveMemoProjection

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

    suspend fun replaceTagRefsForMemo(projection: ActiveMemoProjection) {
        deleteTagRefsByMemoId(projection.entity.id)
        val refs = projection.tagRefs
        if (refs.isNotEmpty()) {
            insertTagRefs(refs)
        }
    }

    suspend fun replaceTagRefsForMemos(projections: List<ActiveMemoProjection>) {
        if (projections.isEmpty()) return
        projections.map { it.entity.id }.chunked(ROOM_MAX_BIND_PARAMETER_COUNT).forEach { chunk ->
            deleteTagRefsByMemoIds(chunk)
        }
        val refs = projections.asSequence().flatMap { it.tagRefs.asSequence() }.toList()
        if (refs.isNotEmpty()) {
            insertTagRefs(refs)
        }
    }
}
