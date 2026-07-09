package com.lomo.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.lomo.data.local.entity.WebDavLocalFingerprintEntity

@Dao
interface WebDavLocalFingerprintDao {
    @Query(
        """
        SELECT * FROM webdav_local_fingerprint
        WHERE workspace_generation = :workspaceGeneration AND path = :path
        """,
    )
    suspend fun getByPath(
        path: String,
        workspaceGeneration: String,
    ): WebDavLocalFingerprintEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WebDavLocalFingerprintEntity)

    @Query("DELETE FROM webdav_local_fingerprint WHERE workspace_generation = :workspaceGeneration")
    suspend fun clearAll(workspaceGeneration: String)

    @Query(
        """
        DELETE FROM webdav_local_fingerprint
        WHERE workspace_generation = :workspaceGeneration AND path NOT IN (:paths)
        """,
    )
    suspend fun deleteByExcludedPaths(
        paths: Collection<String>,
        workspaceGeneration: String,
    )

    suspend fun deleteExcept(
        paths: Collection<String>,
        workspaceGeneration: String,
    ) {
        if (paths.isEmpty()) {
            clearAll(workspaceGeneration)
        } else {
            deleteByExcludedPaths(paths = paths, workspaceGeneration = workspaceGeneration)
        }
    }
}
