package com.lomo.data.local.dao

import androidx.room3.Dao
import androidx.room3.Query
import com.lomo.data.local.entity.MemoEntity
import kotlin.random.Random

data class MemoRowIdBounds(
    val minRowId: Long?,
    val maxRowId: Long?,
    val totalCount: Int,
)

private data class RandomMemoPageRequest(
    val cappedLimit: Int,
    val rowIdFloor: Long,
)

private data class RandomMemoRowIdWindow(
    val minRowId: Long,
    val maxRowId: Long,
    val totalCount: Int,
)

@Dao
interface MemoRandomDao {
    @Query("SELECT MIN(rowid) AS minRowId, MAX(rowid) AS maxRowId, COUNT(*) AS totalCount FROM Lomo")
    suspend fun getRandomMemoRowIdBounds(): MemoRowIdBounds

    @Query("SELECT * FROM Lomo WHERE rowid >= :rowIdFloor ORDER BY rowid ASC LIMIT :limit")
    suspend fun getRandomMemosFromRowIdFloor(
        rowIdFloor: Long,
        limit: Int,
    ): List<MemoEntity>

    @Query("SELECT * FROM Lomo WHERE rowid < :rowIdFloor ORDER BY rowid ASC LIMIT :limit")
    suspend fun getRandomMemosBeforeRowIdFloor(
        rowIdFloor: Long,
        limit: Int,
    ): List<MemoEntity>

    suspend fun getRandomMemos(limit: Int): List<MemoEntity> {
        val request = randomMemoPageRequest(limit)
        return if (request == null) {
            emptyList()
        } else {
            getRandomMemos(request)
        }
    }

    fun nextRandomMemoRowIdFloor(
        minRowId: Long,
        maxRowId: Long,
    ): Long = randomRowIdFloor(minRowId = minRowId, maxRowId = maxRowId)

    private suspend fun randomMemoPageRequest(limit: Int): RandomMemoPageRequest? {
        val window = if (limit > 0) getRandomMemoRowIdBounds().toRandomMemoRowIdWindow() else null
        return if (window == null) {
            null
        } else {
            RandomMemoPageRequest(
                cappedLimit = limit.coerceAtMost(window.totalCount),
                rowIdFloor = nextRandomMemoRowIdFloor(minRowId = window.minRowId, maxRowId = window.maxRowId),
            )
        }
    }

    private suspend fun getRandomMemos(request: RandomMemoPageRequest): List<MemoEntity> {
        val fromFloor =
            getRandomMemosFromRowIdFloor(
                rowIdFloor = request.rowIdFloor,
                limit = request.cappedLimit,
            )
        val remainingLimit = request.cappedLimit - fromFloor.size
        return if (remainingLimit > 0) {
            fromFloor + getRandomMemosBeforeRowIdFloor(rowIdFloor = request.rowIdFloor, limit = remainingLimit)
        } else {
            fromFloor
        }
    }
}

private fun MemoRowIdBounds.toRandomMemoRowIdWindow(): RandomMemoRowIdWindow? {
    val minRowId = minRowId
    val maxRowId = maxRowId
    return when {
        minRowId == null || maxRowId == null -> null
        totalCount <= 0 || maxRowId < minRowId -> null
        else ->
            RandomMemoRowIdWindow(
                minRowId = minRowId,
                maxRowId = maxRowId,
                totalCount = totalCount,
            )
    }
}

private fun randomRowIdFloor(
    minRowId: Long,
    maxRowId: Long,
): Long {
    if (minRowId == maxRowId) return minRowId
    return minRowId + (Random.nextDouble() * (maxRowId - minRowId + 1)).toLong()
}
