package com.lomo.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoImageAttachmentEntity
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.data.local.entity.toImageAttachmentRefs

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

    suspend fun replaceImageRefsForMemo(memo: MemoEntity) {
        deleteImageRefsByMemoId(memo.id)
        val refs = memo.toImageAttachmentRefs()
        if (refs.isNotEmpty()) {
            insertImageRefs(refs)
        }
    }

    suspend fun replaceImageRefsForTrashMemo(memo: TrashMemoEntity) {
        deleteImageRefsByMemoId(memo.id)
        val refs = memo.toImageAttachmentRefs()
        if (refs.isNotEmpty()) {
            insertImageRefs(refs)
        }
    }

    suspend fun replaceImageRefsForMemos(memos: List<MemoEntity>) {
        if (memos.isEmpty()) return
        deleteImageRefsByMemoIds(memos.map(MemoEntity::id))
        val refs = memos.asSequence().flatMap { it.toImageAttachmentRefs().asSequence() }.toList()
        if (refs.isNotEmpty()) {
            insertImageRefs(refs)
        }
    }

    suspend fun replaceImageRefsForTrashMemos(memos: List<TrashMemoEntity>) {
        if (memos.isEmpty()) return
        deleteImageRefsByMemoIds(memos.map(TrashMemoEntity::id))
        val refs = memos.asSequence().flatMap { it.toImageAttachmentRefs().asSequence() }.toList()
        if (refs.isNotEmpty()) {
            insertImageRefs(refs)
        }
    }
}
