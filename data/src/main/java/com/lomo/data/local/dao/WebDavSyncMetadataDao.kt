package com.lomo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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
