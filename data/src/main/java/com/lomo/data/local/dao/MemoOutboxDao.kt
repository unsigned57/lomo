package com.lomo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.lomo.data.local.entity.MemoFileOutboxEntity

@Dao
interface MemoOutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemoFileOutbox(item: MemoFileOutboxEntity): Long

    @Query("SELECT * FROM MemoFileOutbox ORDER BY id ASC LIMIT :limit")
    suspend fun getMemoFileOutboxBatch(limit: Int): List<MemoFileOutboxEntity>

    @Query(
        """
        UPDATE MemoFileOutbox
        SET claimToken = :claimToken,
            claimUpdatedAt = :claimedAt,
            updatedAt = :claimedAt
        WHERE id = (
            SELECT id FROM MemoFileOutbox
            WHERE claimToken IS NULL
               OR claimUpdatedAt <= :staleBefore
            ORDER BY id ASC
            LIMIT 1
        )
        """,
    )
    suspend fun claimNextMemoFileOutboxRow(
        claimToken: String,
        claimedAt: Long,
        staleBefore: Long,
    ): Int

    @Query("SELECT * FROM MemoFileOutbox WHERE claimToken = :claimToken LIMIT 1")
    suspend fun getMemoFileOutboxByClaimToken(claimToken: String): MemoFileOutboxEntity?

    @Transaction
    suspend fun claimNextMemoFileOutbox(
        claimToken: String,
        claimedAt: Long,
        staleBefore: Long,
    ): MemoFileOutboxEntity? {
        val claimed = claimNextMemoFileOutboxRow(claimToken, claimedAt, staleBefore)
        if (claimed <= 0) return null
        return getMemoFileOutboxByClaimToken(claimToken)
    }

    @Query("DELETE FROM MemoFileOutbox WHERE id = :id")
    suspend fun deleteMemoFileOutboxById(id: Long)

    @Query("DELETE FROM MemoFileOutbox")
    suspend fun clearMemoFileOutbox()

    @Query(
        """
        UPDATE MemoFileOutbox
        SET retryCount = retryCount + 1,
            updatedAt = :updatedAt,
            lastError = :lastError,
            claimToken = NULL,
            claimUpdatedAt = NULL
        WHERE id = :id
        """,
    )
    suspend fun markMemoFileOutboxFailed(
        id: Long,
        updatedAt: Long,
        lastError: String?,
    )

    @Query("SELECT COUNT(*) FROM MemoFileOutbox")
    suspend fun getMemoFileOutboxCount(): Int
}
