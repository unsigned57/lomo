package com.lomo.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.lomo.data.local.entity.MemoFileOutboxEntity

@Dao
interface MemoOutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMemoFileOutboxIgnoringDuplicate(item: MemoFileOutboxEntity): Long

    @Query("SELECT id FROM MemoFileOutbox WHERE idempotencyKey = :idempotencyKey LIMIT 1")
    suspend fun getMemoFileOutboxIdByIdempotencyKey(idempotencyKey: String): Long?

    @Transaction
    suspend fun insertMemoFileOutbox(item: MemoFileOutboxEntity): Long {
        val insertedId = insertMemoFileOutboxIgnoringDuplicate(item)
        if (insertedId != -1L) return insertedId
        return requireNotNull(getMemoFileOutboxIdByIdempotencyKey(item.idempotencyKey)) {
            "Memo file outbox duplicate insert did not resolve existing id for ${item.idempotencyKey}"
        }
    }

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
