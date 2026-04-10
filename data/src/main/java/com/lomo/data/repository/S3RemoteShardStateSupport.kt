package com.lomo.data.repository

import com.lomo.data.local.dao.S3RemoteShardStateDao
import com.lomo.data.local.dao.S3RemoteShardScheduleTelemetrySnapshot
import com.lomo.data.local.entity.S3RemoteShardStateEntity
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

object DisabledS3RemoteShardStateStore : S3RemoteShardStateStore {
    override val remoteShardStateEnabled: Boolean = false

    override suspend fun readAll(): List<S3RemoteShardState> = emptyList()

    override suspend fun readByBucketId(bucketId: String): S3RemoteShardState? = null

    override suspend fun readByBucketIds(bucketIds: Collection<String>): List<S3RemoteShardState> = emptyList()

    override suspend fun readMostSpecificAncestor(relativePrefix: String?): S3RemoteShardState? = null

    override suspend fun readScheduleTelemetry(
        now: Long,
        reconcileInterval: Duration,
        endpointProfile: S3EndpointProfile,
    ): S3RemoteShardScheduleTelemetry =
        S3RemoteShardScheduleTelemetry(
            shardCount = 0,
            oldestScanAt = null,
            hasElevatedChangePressure = false,
            hasHighVerificationUncertainty = false,
        )

    override suspend fun upsert(states: Collection<S3RemoteShardState>) = Unit

    override suspend fun clear() = Unit
}

class InMemoryS3RemoteShardStateStore(
    override val remoteShardStateEnabled: Boolean = true,
) : S3RemoteShardStateStore {
    private val mutex = Mutex()
    private val states = linkedMapOf<String, S3RemoteShardState>()

    override suspend fun readAll(): List<S3RemoteShardState> = mutex.withLock { states.values.toList() }

    override suspend fun readByBucketId(bucketId: String): S3RemoteShardState? = mutex.withLock { states[bucketId] }

    override suspend fun readByBucketIds(bucketIds: Collection<String>): List<S3RemoteShardState> =
        mutex.withLock { bucketIds.mapNotNull(states::get) }

    override suspend fun readMostSpecificAncestor(relativePrefix: String?): S3RemoteShardState? =
        mutex.withLock {
            val normalizedPrefix = relativePrefix?.trim()?.trim('/')?.takeIf(String::isNotBlank) ?: return@withLock null
            states.values
                .filter { state ->
                    val candidatePrefix = state.relativePrefix?.trim()?.trim('/')
                    candidatePrefix != null &&
                        (normalizedPrefix == candidatePrefix || normalizedPrefix.startsWith("$candidatePrefix/"))
                }.maxByOrNull { state ->
                    state.relativePrefix?.length ?: 0
                }
        }

    override suspend fun readScheduleTelemetry(
        now: Long,
        reconcileInterval: Duration,
        endpointProfile: S3EndpointProfile,
    ): S3RemoteShardScheduleTelemetry =
        mutex.withLock { states.values.toList().toScheduleTelemetry(now, reconcileInterval, endpointProfile) }

    override suspend fun upsert(states: Collection<S3RemoteShardState>) {
        if (states.isEmpty()) return
        mutex.withLock {
            states.forEach { state -> this.states[state.bucketId] = state }
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            states.clear()
        }
    }
}

@Singleton
class RoomBackedS3RemoteShardStateStore
    @Inject
    constructor(
        private val dao: S3RemoteShardStateDao,
    ) : S3RemoteShardStateStore {
        override val remoteShardStateEnabled: Boolean = true

        override suspend fun readAll(): List<S3RemoteShardState> =
            dao.getAll().map(S3RemoteShardStateEntity::toModel)

        override suspend fun readByBucketId(bucketId: String): S3RemoteShardState? =
            dao.getByBucketId(bucketId)?.toModel()

        override suspend fun readByBucketIds(bucketIds: Collection<String>): List<S3RemoteShardState> =
            if (bucketIds.isEmpty()) {
                emptyList()
            } else {
                dao.getByBucketIds(bucketIds.toList()).map(S3RemoteShardStateEntity::toModel)
            }

        override suspend fun readMostSpecificAncestor(relativePrefix: String?): S3RemoteShardState? {
            val normalizedPrefix = relativePrefix?.trim()?.trim('/')?.takeIf(String::isNotBlank) ?: return null
            return dao.getMostSpecificAncestor(normalizedPrefix)?.toModel()
        }

        override suspend fun readScheduleTelemetry(
            now: Long,
            reconcileInterval: Duration,
            endpointProfile: S3EndpointProfile,
        ): S3RemoteShardScheduleTelemetry =
            dao.getScheduleTelemetry(
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
            dao.upsertAll(states.map(S3RemoteShardState::toEntity))
        }

        override suspend fun clear() {
            dao.clearAll()
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal interface S3RemoteShardStateBindingsModule {
    @Binds
    fun bindS3RemoteShardStateStore(impl: RoomBackedS3RemoteShardStateStore): S3RemoteShardStateStore
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

private fun S3RemoteShardState.toEntity(): S3RemoteShardStateEntity =
    S3RemoteShardStateEntity(
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

private fun List<S3RemoteShardState>.toScheduleTelemetry(
    now: Long,
    reconcileInterval: Duration,
    endpointProfile: S3EndpointProfile = S3EndpointProfile.GENERIC_S3,
): S3RemoteShardScheduleTelemetry {
    val recentChangeWindowMillis = reconcileInterval.toMillis() / S3_RECENT_CHANGE_WINDOW_DIVISOR
    val uncertaintyWindowMillis = reconcileInterval.toMillis()
    return S3RemoteShardScheduleTelemetry(
        shardCount = size,
        oldestScanAt = minOfOrNull(S3RemoteShardState::lastScannedAt),
        hasElevatedChangePressure =
            any { state ->
                state.idleScanStreak == 0 &&
                    state.scanAgeMillis(now) <= recentChangeWindowMillis &&
                    state.changeRate() >= endpointProfile.changePressureThreshold
            },
        hasHighVerificationUncertainty =
            any { state ->
                state.scanAgeMillis(now) <= uncertaintyWindowMillis &&
                    state.lastVerificationAttemptCount >= endpointProfile.minUncertaintyAttempts &&
                    state.lastVerificationFailureCount >= endpointProfile.minUncertaintyFailures &&
                    state.verificationFailureRate() >= endpointProfile.verificationFailureThreshold
            },
    )
}

private fun S3RemoteShardScheduleTelemetrySnapshot.toModel(): S3RemoteShardScheduleTelemetry =
    S3RemoteShardScheduleTelemetry(
        shardCount = shardCount,
        oldestScanAt = oldestScanAt,
        hasElevatedChangePressure = hasElevatedChangePressure != 0,
        hasHighVerificationUncertainty = hasHighVerificationUncertainty != 0,
    )

private fun S3RemoteShardState.scanAgeMillis(now: Long): Long = (now - lastScannedAt).coerceAtLeast(0L)

private fun S3RemoteShardState.changeRate(): Double =
    lastChangeCount.toDouble() / lastObjectCount.coerceAtLeast(1).toDouble()

private fun S3RemoteShardState.verificationFailureRate(): Double =
    lastVerificationFailureCount.toDouble() / lastVerificationAttemptCount.coerceAtLeast(1).toDouble()

internal const val S3_RECENT_CHANGE_WINDOW_DIVISOR = 2L
