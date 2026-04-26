package com.lomo.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.lomo.data.local.entity.WebDavSyncMetadataEntity

@Dao
interface WebDavSyncMetadataDao {
    @Query("SELECT * FROM webdav_sync_metadata")
    suspend fun getAll(): List<WebDavSyncMetadataEntity>

    @Query("SELECT * FROM webdav_sync_metadata WHERE relative_path IN (:relativePaths)")
    suspend fun getByRelativePaths(relativePaths: List<String>): List<WebDavSyncMetadataEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<WebDavSyncMetadataEntity>)

    @Query("DELETE FROM webdav_sync_metadata WHERE relative_path = :relativePath")
    suspend fun deleteByRelativePath(relativePath: String)

    @Query("DELETE FROM webdav_sync_metadata WHERE relative_path IN (:relativePaths)")
    suspend fun deleteByRelativePaths(relativePaths: List<String>)

    @Query("DELETE FROM webdav_sync_metadata")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceAll(entities: List<WebDavSyncMetadataEntity>) {
        clearAll()
        upsertAll(entities)
    }
}
