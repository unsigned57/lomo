package com.lomo.data.local.dao

import androidx.room3.Dao
import androidx.room3.Query

@Dao
interface MemoIdentityDao {
    @Query("SELECT COUNT(*) FROM Lomo WHERE id GLOB :globPattern")
    suspend fun countMemosByIdGlob(globPattern: String): Int
}
