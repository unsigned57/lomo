package com.lomo.data.local.dao

import androidx.room3.Dao
import androidx.room3.Query
import com.lomo.data.local.entity.MemoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoBrowseDao {
    @Query(
        """
        SELECT * FROM Lomo
        WHERE timestamp >= :startTimestampInclusive AND timestamp < :endTimestampExclusive
        ORDER BY timestamp DESC, id DESC
        """,
    )
    fun getMemosByTimestampRangeFlow(
        startTimestampInclusive: Long,
        endTimestampExclusive: Long,
    ): Flow<List<MemoEntity>>

    @Query(
        """
        SELECT DISTINCT Lomo.*
        FROM Lomo
        INNER JOIN MemoImageAttachment ON MemoImageAttachment.memoId = Lomo.id
        ORDER BY Lomo.timestamp DESC, Lomo.id DESC
        """,
    )
    fun getGalleryMemosFlow(): Flow<List<MemoEntity>>
}
