package com.lomo.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.lomo.data.local.entity.S3LocalChangeJournalEntity

@Dao
interface S3LocalChangeJournalDao {
    @Query("SELECT * FROM s3_local_change_journal ORDER BY id ASC")
    suspend fun getAll(): List<S3LocalChangeJournalEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: S3LocalChangeJournalEntity)

    @Query("DELETE FROM s3_local_change_journal WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: Collection<String>)

    @Query("DELETE FROM s3_local_change_journal")
    suspend fun clearAll()
}
