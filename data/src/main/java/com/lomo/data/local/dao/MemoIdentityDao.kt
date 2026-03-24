package com.lomo.data.local.dao

import androidx.room.Dao
import androidx.room.Query

@Dao
interface MemoIdentityDao {
    @Query(
        """
        SELECT COUNT(*) FROM Lomo
        WHERE id = :baseId OR id GLOB :globPattern
        """,
    )
    suspend fun countMemoIdCollisions(
        baseId: String,
        globPattern: String,
    ): Int

    @Query("SELECT COUNT(*) FROM Lomo WHERE id GLOB :globPattern")
    suspend fun countMemosByIdGlob(globPattern: String): Int
}
