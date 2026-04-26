package com.lomo.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.lomo.data.local.entity.WebDavLocalFingerprintEntity

@Dao
interface WebDavLocalFingerprintDao {
    @Query("SELECT * FROM webdav_local_fingerprint WHERE path = :path")
    suspend fun getByPath(path: String): WebDavLocalFingerprintEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WebDavLocalFingerprintEntity)

    @Query("DELETE FROM webdav_local_fingerprint")
    suspend fun clearAll()

    @Query("DELETE FROM webdav_local_fingerprint WHERE path NOT IN (:paths)")
    suspend fun deleteByExcludedPaths(paths: Collection<String>)

    suspend fun deleteExcept(paths: Collection<String>) {
        if (paths.isEmpty()) {
            clearAll()
        } else {
            deleteByExcludedPaths(paths)
        }
    }
}
