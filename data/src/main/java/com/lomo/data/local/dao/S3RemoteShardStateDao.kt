package com.lomo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lomo.data.local.entity.S3RemoteShardStateEntity

@Dao
interface S3RemoteShardStateDao {
    @Query("SELECT * FROM s3_remote_shard_state")
    suspend fun getAll(): List<S3RemoteShardStateEntity>

    @Query("SELECT * FROM s3_remote_shard_state WHERE bucket_id = :bucketId")
    suspend fun getByBucketId(bucketId: String): S3RemoteShardStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<S3RemoteShardStateEntity>)

    @Query("DELETE FROM s3_remote_shard_state")
    suspend fun clearAll()
}
