package com.lomo.data.repository

import com.lomo.data.local.dao.S3RemoteShardStateDao
import com.lomo.data.local.dao.S3RemoteShardScheduleTelemetrySnapshot
import com.lomo.data.local.entity.S3RemoteShardStateEntity
import com.lomo.domain.repository.WorkspaceSyncGenerationProvider
import java.time.Duration


data class S3RemoteShardState(
    val bucketId: String,
    val relativePrefix: String?,
    val lastScannedAt: Long,
    val lastObjectCount: Int = 0,
    val lastDurationMs: Long = 0L,
    val lastChangeCount: Int = 0,
    val idleScanStreak: Int = 0,
    val lastVerificationAttemptCount: Int = 0,
    val lastVerificationFailureCount: Int = 0,
)

data class S3RemoteShardScheduleTelemetry(
    val shardCount: Int,
    val oldestScanAt: Long?,
    val hasElevatedChangePressure: Boolean,
    val hasHighVerificationUncertainty: Boolean,
)

interface S3RemoteShardStateStore {
    val remoteShardStateEnabled: Boolean

    suspend fun readAll(): List<S3RemoteShardState>

    suspend fun readByBucketId(bucketId: String): S3RemoteShardState?

    suspend fun readByBucketIds(bucketIds: Collection<String>): List<S3RemoteShardState>

    suspend fun readMostSpecificAncestor(relativePrefix: String?): S3RemoteShardState?

    suspend fun readScheduleTelemetry(
        now: Long,
        reconcileInterval: Duration,
        endpointProfile: S3EndpointProfile = S3EndpointProfile.GENERIC_S3,
    ): S3RemoteShardScheduleTelemetry

    suspend fun upsert(states: Collection<S3RemoteShardState>)

    suspend fun clear()
}

class RoomBackedS3RemoteShardStateStore(
    private val dao: S3RemoteShardStateDao,
    private val generationProvider: WorkspaceSyncGenerationProvider,
) : S3RemoteShardStateStore {
        override val remoteShardStateEnabled: Boolean = true

        override suspend fun readAll(): List<S3RemoteShardState> =
            dao.getAll(activeGeneration()).map(S3RemoteShardStateEntity::toModel)

        override suspend fun readByBucketId(bucketId: String): S3RemoteShardState? =
            dao.getByBucketId(bucketId = bucketId, workspaceGeneration = activeGeneration())?.toModel()

        override suspend fun readByBucketIds(bucketIds: Collection<String>): List<S3RemoteShardState> =
            if (bucketIds.isEmpty()) {
                emptyList()
            } else {
                dao.getByBucketIds(bucketIds = bucketIds.toList(), workspaceGeneration = activeGeneration())
                    .map(S3RemoteShardStateEntity::toModel)
            }

        override suspend fun readMostSpecificAncestor(relativePrefix: String?): S3RemoteShardState? {
            val normalizedPrefix = relativePrefix?.trim()?.trim('/')?.takeIf(String::isNotBlank) ?: return null
            return dao
                .getMostSpecificAncestor(
                    relativePrefix = normalizedPrefix,
                    workspaceGeneration = activeGeneration(),
                )?.toModel()
        }

        override suspend fun readScheduleTelemetry(
            now: Long,
            reconcileInterval: Duration,
            endpointProfile: S3EndpointProfile,
        ): S3RemoteShardScheduleTelemetry =
            dao.getScheduleTelemetry(
                workspaceGeneration = activeGeneration(),
                now = now,
                recentChangeWindowMs = reconcileInterval.toMillis() / S3_RECENT_CHANGE_WINDOW_DIVISOR,
                uncertaintyWindowMs = reconcileInterval.toMillis(),
                changePressureThreshold = endpointProfile.changePressureThreshold,
                verificationFailureThreshold = endpointProfile.verificationFailureThreshold,
                minUncertaintyAttempts = endpointProfile.minUncertaintyAttempts,
                minUncertaintyFailures = endpointProfile.minUncertaintyFailures,
            ).toModel()

        override suspend fun upsert(states: Collection<S3RemoteShardState>) {
            if (states.isEmpty()) return
            val generation = activeGeneration()
            dao.upsertAll(states.map { state -> state.toEntity(generation) })
        }

        override suspend fun clear() {
            dao.clearAll(activeGeneration())
        }

        private suspend fun activeGeneration(): String = generationProvider.activeGeneration().value
    }


private fun S3RemoteShardStateEntity.toModel(): S3RemoteShardState =
    S3RemoteShardState(
        bucketId = bucketId,
        relativePrefix = relativePrefix,
        lastScannedAt = lastScannedAt,
        lastObjectCount = lastObjectCount,
        lastDurationMs = lastDurationMs,
        lastChangeCount = lastChangeCount,
        idleScanStreak = idleScanStreak,
        lastVerificationAttemptCount = lastVerificationAttemptCount,
        lastVerificationFailureCount = lastVerificationFailureCount,
    )

private fun S3RemoteShardState.toEntity(workspaceGeneration: String): S3RemoteShardStateEntity =
    S3RemoteShardStateEntity(
        workspaceGeneration = workspaceGeneration,
        bucketId = bucketId,
        relativePrefix = relativePrefix,
        lastScannedAt = lastScannedAt,
        lastObjectCount = lastObjectCount,
        lastDurationMs = lastDurationMs,
        lastChangeCount = lastChangeCount,
        idleScanStreak = idleScanStreak,
        lastVerificationAttemptCount = lastVerificationAttemptCount,
        lastVerificationFailureCount = lastVerificationFailureCount,
    )

private fun S3RemoteShardScheduleTelemetrySnapshot.toModel(): S3RemoteShardScheduleTelemetry =
    S3RemoteShardScheduleTelemetry(
        shardCount = shardCount,
        oldestScanAt = oldestScanAt,
        hasElevatedChangePressure = hasElevatedChangePressure != 0,
        hasHighVerificationUncertainty = hasHighVerificationUncertainty != 0,
    )

internal const val S3_RECENT_CHANGE_WINDOW_DIVISOR = 2L
