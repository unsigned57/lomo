package com.lomo.data.repository

import com.lomo.data.local.dao.S3RemoteShardStateDao
import com.lomo.data.local.entity.S3RemoteShardStateEntity
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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

interface S3RemoteShardStateStore {
    val remoteShardStateEnabled: Boolean

    suspend fun readAll(): List<S3RemoteShardState>

    suspend fun readByBucketId(bucketId: String): S3RemoteShardState?

    suspend fun upsert(states: Collection<S3RemoteShardState>)

    suspend fun clear()
}

object DisabledS3RemoteShardStateStore : S3RemoteShardStateStore {
    override val remoteShardStateEnabled: Boolean = false

    override suspend fun readAll(): List<S3RemoteShardState> = emptyList()

    override suspend fun readByBucketId(bucketId: String): S3RemoteShardState? = null

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
