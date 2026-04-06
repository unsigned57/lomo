package com.lomo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lomo.data.local.entity.S3RemoteShardStateEntity

data class S3RemoteShardScheduleTelemetrySnapshot(
    val shardCount: Int,
    val oldestScanAt: Long?,
    val hasElevatedChangePressure: Int,
    val hasHighVerificationUncertainty: Int,
)

@Dao
interface S3RemoteShardStateDao {
    @Query("SELECT * FROM s3_remote_shard_state")
    suspend fun getAll(): List<S3RemoteShardStateEntity>

    @Query("SELECT * FROM s3_remote_shard_state WHERE bucket_id = :bucketId")
    suspend fun getByBucketId(bucketId: String): S3RemoteShardStateEntity?

    @Query("SELECT * FROM s3_remote_shard_state WHERE bucket_id IN (:bucketIds)")
    suspend fun getByBucketIds(bucketIds: List<String>): List<S3RemoteShardStateEntity>

    @Query(
        """
        SELECT *
        FROM s3_remote_shard_state
        WHERE relative_prefix IS NOT NULL
            AND (:relativePrefix = relative_prefix OR :relativePrefix LIKE relative_prefix || '/%')
        ORDER BY LENGTH(relative_prefix) DESC
        LIMIT 1
        """,
    )
    suspend fun getMostSpecificAncestor(relativePrefix: String): S3RemoteShardStateEntity?

    @Query(
        """
        SELECT
            COUNT(*) AS shardCount,
            MIN(last_scanned_at) AS oldestScanAt,
            MAX(
                CASE
                    WHEN idle_scan_streak = 0
                        AND last_object_count > 0
                        AND (:now - last_scanned_at) <= :recentChangeWindowMs
                        AND (CAST(last_change_count AS REAL) / CAST(last_object_count AS REAL)) >= :changePressureThreshold
                    THEN 1
                    ELSE 0
                END
            ) AS hasElevatedChangePressure,
            MAX(
                CASE
                    WHEN last_verification_attempt_count >= :minUncertaintyAttempts
                        AND last_verification_failure_count >= :minUncertaintyFailures
                        AND last_verification_attempt_count > 0
                        AND (:now - last_scanned_at) <= :uncertaintyWindowMs
                        AND (CAST(last_verification_failure_count AS REAL) / CAST(last_verification_attempt_count AS REAL)) >= :verificationFailureThreshold
                    THEN 1
                    ELSE 0
                END
            ) AS hasHighVerificationUncertainty
        FROM s3_remote_shard_state
        """,
    )
    suspend fun getScheduleTelemetry(
        now: Long,
        recentChangeWindowMs: Long,
        uncertaintyWindowMs: Long,
        changePressureThreshold: Double,
        verificationFailureThreshold: Double,
        minUncertaintyAttempts: Int,
        minUncertaintyFailures: Int,
    ): S3RemoteShardScheduleTelemetrySnapshot

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<S3RemoteShardStateEntity>)

    @Query("DELETE FROM s3_remote_shard_state")
    suspend fun clearAll()
}
