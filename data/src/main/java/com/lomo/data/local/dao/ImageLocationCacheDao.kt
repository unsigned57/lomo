package com.lomo.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.lomo.data.local.entity.ImageLocationCacheEntity

@Dao
interface ImageLocationCacheDao {
    @Query("SELECT * FROM image_location_cache")
    suspend fun readAll(): List<ImageLocationCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<ImageLocationCacheEntity>)

    @Query("DELETE FROM image_location_cache")
    suspend fun clearAll()
}
