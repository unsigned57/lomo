package com.lomo.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.lomo.data.local.entity.MemoImageAttachmentEntity
import com.lomo.data.local.projection.ActiveMemoProjection
import com.lomo.data.local.projection.TrashMemoProjection

@Dao
interface MemoImageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImageRefs(refs: List<MemoImageAttachmentEntity>)

    @Query("DELETE FROM MemoImageAttachment WHERE memoId = :memoId")
    suspend fun deleteImageRefsByMemoId(memoId: String)

    @Query("DELETE FROM MemoImageAttachment WHERE memoId IN (:memoIds)")
    suspend fun deleteImageRefsByMemoIds(memoIds: List<String>)

    @Query("DELETE FROM MemoImageAttachment")
    suspend fun clearImageRefs()

    suspend fun replaceImageRefsForMemo(projection: ActiveMemoProjection) {
        deleteImageRefsByMemoId(projection.entity.id)
        val refs = projection.imageRefs
        if (refs.isNotEmpty()) {
            insertImageRefs(refs)
        }
    }

    suspend fun replaceImageRefsForTrashMemo(projection: TrashMemoProjection) {
        deleteImageRefsByMemoId(projection.entity.id)
        val refs = projection.imageRefs
        if (refs.isNotEmpty()) {
            insertImageRefs(refs)
        }
    }

    suspend fun replaceImageRefsForMemos(projections: List<ActiveMemoProjection>) {
        if (projections.isEmpty()) return
        projections.map { it.entity.id }.chunked(ROOM_MAX_BIND_PARAMETER_COUNT).forEach { chunk ->
            deleteImageRefsByMemoIds(chunk)
        }
        val refs = projections.asSequence().flatMap { it.imageRefs.asSequence() }.toList()
        if (refs.isNotEmpty()) {
            insertImageRefs(refs)
        }
    }

    suspend fun replaceImageRefsForTrashMemos(projections: List<TrashMemoProjection>) {
        if (projections.isEmpty()) return
        projections.map { it.entity.id }.chunked(ROOM_MAX_BIND_PARAMETER_COUNT).forEach { chunk ->
            deleteImageRefsByMemoIds(chunk)
        }
        val refs = projections.asSequence().flatMap { it.imageRefs.asSequence() }.toList()
        if (refs.isNotEmpty()) {
            insertImageRefs(refs)
        }
    }
}
