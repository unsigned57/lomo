package com.lomo.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.lomo.data.local.entity.WebDavSyncMetadataEntity

interface WebDavSyncMetadataDao {
    suspend fun getAll(): List<WebDavSyncMetadataEntity>

    suspend fun getByRelativePaths(relativePaths: List<String>): List<WebDavSyncMetadataEntity>

    suspend fun upsertAll(entities: List<WebDavSyncMetadataEntity>)

    suspend fun deleteByRelativePath(relativePath: String)

    suspend fun deleteByRelativePaths(relativePaths: List<String>)

    suspend fun clearAll()

    suspend fun replaceAll(entities: List<WebDavSyncMetadataEntity>) {
        clearAll()
        upsertAll(entities)
    }
}

@Dao
interface RawWebDavSyncMetadataDao {
    @Query("SELECT * FROM webdav_sync_metadata WHERE workspace_generation = :workspaceGeneration")
    suspend fun getAll(workspaceGeneration: String): List<WebDavSyncMetadataEntity>

    @Query(
        """
        SELECT * FROM webdav_sync_metadata
        WHERE workspace_generation = :workspaceGeneration AND relative_path IN (:relativePaths)
        """,
    )
    suspend fun getByRelativePaths(
        relativePaths: List<String>,
        workspaceGeneration: String,
    ): List<WebDavSyncMetadataEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<WebDavSyncMetadataEntity>)

    @Query(
        """
        DELETE FROM webdav_sync_metadata
        WHERE workspace_generation = :workspaceGeneration AND relative_path = :relativePath
        """,
    )
    suspend fun deleteByRelativePath(
        relativePath: String,
        workspaceGeneration: String,
    )

    @Query(
        """
        DELETE FROM webdav_sync_metadata
        WHERE workspace_generation = :workspaceGeneration AND relative_path IN (:relativePaths)
        """,
    )
    suspend fun deleteByRelativePaths(
        relativePaths: List<String>,
        workspaceGeneration: String,
    )

    @Query("DELETE FROM webdav_sync_metadata WHERE workspace_generation = :workspaceGeneration")
    suspend fun clearAll(workspaceGeneration: String)

    @Transaction
    suspend fun replaceAll(
        entities: List<WebDavSyncMetadataEntity>,
        workspaceGeneration: String,
    ) {
        clearAll(workspaceGeneration)
        upsertAll(entities)
    }
}
