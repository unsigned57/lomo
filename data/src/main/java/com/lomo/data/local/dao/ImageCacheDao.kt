package com.lomo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lomo.data.local.entity.ImageCacheEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ImageCacheDao {
    @Query("SELECT * FROM image_cache")
    fun getAllImages(): Flow<List<ImageCacheEntity>>

    @Query("SELECT * FROM image_cache")
    suspend fun getAllImagesSync(): List<ImageCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImages(images: List<ImageCacheEntity>)

    @Query("DELETE FROM image_cache")
    suspend fun clearAll()

    @Query("DELETE FROM image_cache WHERE filename IN (:filenames)")
    suspend fun deleteByFilenames(filenames: List<String>)
}
