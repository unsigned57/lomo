package com.lomo.data.local.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.lomo.data.local.entity.LocalFileStateEntity

@Dao
interface LocalFileStateDao {
    @Query("SELECT * FROM local_file_state WHERE filename = :filename AND isTrash = :isTrash")
    suspend fun getByFilename(
        filename: String,
        isTrash: Boolean,
    ): LocalFileStateEntity?

    @Query("SELECT * FROM local_file_state")
    suspend fun getAll(): List<LocalFileStateEntity>

    @Query("SELECT * FROM local_file_state WHERE isTrash = :isTrash")
    suspend fun getAllByTrashStatus(isTrash: Boolean): List<LocalFileStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalFileStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<LocalFileStateEntity>)

    @Query("DELETE FROM local_file_state WHERE filename = :filename AND isTrash = :isTrash")
    suspend fun deleteByFilename(
        filename: String,
        isTrash: Boolean,
    )

    @Query("DELETE FROM local_file_state")
    suspend fun clearAll()
}
