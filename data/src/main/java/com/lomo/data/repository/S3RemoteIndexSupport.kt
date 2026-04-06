package com.lomo.data.repository

import com.lomo.data.local.dao.S3RemoteIndexDao
import com.lomo.data.local.entity.S3RemoteIndexEntity
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class S3RemoteIndexEntry(
    val relativePath: String,
    val remotePath: String,
    val etag: String?,
    val remoteLastModified: Long?,
    val size: Long?,
    val lastSeenAt: Long,
    val lastVerifiedAt: Long?,
    val scanBucket: String,
    val scanPriority: Int = 0,
    val dirtySuspect: Boolean = false,
    val missingOnLastScan: Boolean = false,
    val scanEpoch: Long = 0L,
)

interface S3RemoteIndexStore {
    val remoteIndexEnabled: Boolean

    suspend fun readAllRelativePaths(): List<String>

    suspend fun readPresentCount(): Int

    suspend fun readByRelativePaths(relativePaths: Collection<String>): List<S3RemoteIndexEntry>

    suspend fun readByRelativePrefix(relativePrefix: String?): List<S3RemoteIndexEntry>

    suspend fun readOutsideScanBuckets(excludedBuckets: Collection<String>): List<S3RemoteIndexEntry>

    suspend fun readReconcileCandidates(limit: Int): List<S3RemoteIndexEntry>

    suspend fun upsert(entries: Collection<S3RemoteIndexEntry>)

    suspend fun deleteByRelativePaths(relativePaths: Collection<String>)

    suspend fun deleteOutsideScanEpoch(scanEpoch: Long)

    suspend fun replaceAll(entries: Collection<S3RemoteIndexEntry>)

    suspend fun clear()
}

object DisabledS3RemoteIndexStore : S3RemoteIndexStore {
    override val remoteIndexEnabled: Boolean = false

    override suspend fun readAllRelativePaths(): List<String> = emptyList()

    override suspend fun readPresentCount(): Int = 0

    override suspend fun readByRelativePaths(relativePaths: Collection<String>): List<S3RemoteIndexEntry> = emptyList()

    override suspend fun readByRelativePrefix(relativePrefix: String?): List<S3RemoteIndexEntry> = emptyList()

    override suspend fun readOutsideScanBuckets(
        excludedBuckets: Collection<String>,
    ): List<S3RemoteIndexEntry> = emptyList()

    override suspend fun readReconcileCandidates(limit: Int): List<S3RemoteIndexEntry> = emptyList()

    override suspend fun upsert(entries: Collection<S3RemoteIndexEntry>) = Unit

    override suspend fun deleteByRelativePaths(relativePaths: Collection<String>) = Unit

    override suspend fun deleteOutsideScanEpoch(scanEpoch: Long) = Unit

    override suspend fun replaceAll(entries: Collection<S3RemoteIndexEntry>) = Unit

    override suspend fun clear() = Unit
}

class InMemoryS3RemoteIndexStore(
    override val remoteIndexEnabled: Boolean = true,
) : S3RemoteIndexStore {
    private val mutex = Mutex()
    private val entries = linkedMapOf<String, S3RemoteIndexEntry>()

    override suspend fun readAllRelativePaths(): List<String> = mutex.withLock { entries.keys.toList() }

    override suspend fun readPresentCount(): Int =
        mutex.withLock { entries.values.count { entry -> !entry.missingOnLastScan } }

    override suspend fun readByRelativePaths(relativePaths: Collection<String>): List<S3RemoteIndexEntry> =
        mutex.withLock { relativePaths.mapNotNull(entries::get) }

    override suspend fun readByRelativePrefix(relativePrefix: String?): List<S3RemoteIndexEntry> =
        mutex.withLock {
            val normalizedPrefix = relativePrefix?.trim()?.trim('/')?.takeIf(String::isNotBlank)
            entries.values.filter { entry ->
                normalizedPrefix == null ||
                    entry.relativePath == normalizedPrefix ||
                    entry.relativePath.startsWith("$normalizedPrefix/")
            }
        }

    override suspend fun readOutsideScanBuckets(excludedBuckets: Collection<String>): List<S3RemoteIndexEntry> =
        mutex.withLock {
            val excluded = excludedBuckets.toSet()
            entries.values.filterNot { entry -> entry.scanBucket in excluded }
        }

    override suspend fun readReconcileCandidates(limit: Int): List<S3RemoteIndexEntry> =
        mutex.withLock {
            entries.values
                .sortedWith(
                    compareByDescending<S3RemoteIndexEntry> { it.dirtySuspect }
                        .thenByDescending { it.missingOnLastScan }
                        .thenByDescending { it.scanPriority }
                        .thenBy { it.lastVerifiedAt ?: 0L }
                        .thenBy { it.lastSeenAt },
                )
                .take(limit)
        }

    override suspend fun upsert(entries: Collection<S3RemoteIndexEntry>) {
        if (entries.isEmpty()) return
        mutex.withLock {
            entries.forEach { entry -> this.entries[entry.relativePath] = entry }
        }
    }

    override suspend fun deleteByRelativePaths(relativePaths: Collection<String>) {
        if (relativePaths.isEmpty()) return
        mutex.withLock {
            relativePaths.forEach(entries::remove)
        }
    }

    override suspend fun deleteOutsideScanEpoch(scanEpoch: Long) {
        mutex.withLock {
            entries.entries.removeIf { (_, value) -> value.scanEpoch != scanEpoch }
        }
    }

    override suspend fun replaceAll(entries: Collection<S3RemoteIndexEntry>) {
        mutex.withLock {
            this.entries.clear()
            entries.forEach { entry -> this.entries[entry.relativePath] = entry }
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            entries.clear()
        }
    }
}

@Singleton
class RoomBackedS3RemoteIndexStore
    @Inject
    constructor(
        private val dao: S3RemoteIndexDao,
    ) : S3RemoteIndexStore {
        override val remoteIndexEnabled: Boolean = true

        override suspend fun readAllRelativePaths(): List<String> = dao.getAllRelativePaths()

        override suspend fun readPresentCount(): Int = dao.getPresentCount()

        override suspend fun readByRelativePaths(relativePaths: Collection<String>): List<S3RemoteIndexEntry> =
            if (relativePaths.isEmpty()) {
                emptyList()
            } else {
                dao.getByRelativePaths(relativePaths.toList()).map(S3RemoteIndexEntity::toModel)
            }

        override suspend fun readByRelativePrefix(relativePrefix: String?): List<S3RemoteIndexEntry> {
            val normalizedPrefix = relativePrefix?.trim()?.trim('/')?.takeIf(String::isNotBlank)
            return if (normalizedPrefix == null) {
                dao.getAll().map(S3RemoteIndexEntity::toModel)
            } else {
                dao.getByRelativePrefix(normalizedPrefix, "$normalizedPrefix/%").map(S3RemoteIndexEntity::toModel)
            }
        }

        override suspend fun readOutsideScanBuckets(excludedBuckets: Collection<String>): List<S3RemoteIndexEntry> =
            if (excludedBuckets.isEmpty()) {
                dao.getAll().map(S3RemoteIndexEntity::toModel)
            } else {
                dao.getOutsideScanBuckets(excludedBuckets.toList()).map(S3RemoteIndexEntity::toModel)
            }

        override suspend fun readReconcileCandidates(limit: Int): List<S3RemoteIndexEntry> =
            dao.getReconcileCandidates(limit).map(S3RemoteIndexEntity::toModel)

        override suspend fun upsert(entries: Collection<S3RemoteIndexEntry>) {
            if (entries.isEmpty()) return
            dao.upsertAll(entries.map(S3RemoteIndexEntry::toEntity))
        }

        override suspend fun deleteByRelativePaths(relativePaths: Collection<String>) {
            if (relativePaths.isEmpty()) return
            dao.deleteByRelativePaths(relativePaths.toList())
        }

        override suspend fun deleteOutsideScanEpoch(scanEpoch: Long) {
            dao.deleteOutsideScanEpoch(scanEpoch)
        }

        override suspend fun replaceAll(entries: Collection<S3RemoteIndexEntry>) {
            dao.clearAll()
            upsert(entries)
        }

        override suspend fun clear() {
            dao.clearAll()
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal interface S3RemoteIndexBindingsModule {
    @Binds
    fun bindS3RemoteIndexStore(impl: RoomBackedS3RemoteIndexStore): S3RemoteIndexStore
}

private fun S3RemoteIndexEntity.toModel(): S3RemoteIndexEntry =
    S3RemoteIndexEntry(
        relativePath = relativePath,
        remotePath = remotePath,
        etag = etag,
        remoteLastModified = remoteLastModified,
        size = size,
        lastSeenAt = lastSeenAt,
        lastVerifiedAt = lastVerifiedAt,
        scanBucket = scanBucket,
        scanPriority = scanPriority,
        dirtySuspect = dirtySuspect,
        missingOnLastScan = missingOnLastScan,
        scanEpoch = scanEpoch,
    )

private fun S3RemoteIndexEntry.toEntity(): S3RemoteIndexEntity =
    S3RemoteIndexEntity(
        relativePath = relativePath,
        remotePath = remotePath,
        etag = etag,
        remoteLastModified = remoteLastModified,
        size = size,
        lastSeenAt = lastSeenAt,
        lastVerifiedAt = lastVerifiedAt,
        scanBucket = scanBucket,
        scanPriority = scanPriority,
        dirtySuspect = dirtySuspect,
        missingOnLastScan = missingOnLastScan,
        scanEpoch = scanEpoch,
    )

suspend fun S3RemoteIndexStore.readAll(): List<S3RemoteIndexEntry> =
    readByRelativePaths(readAllRelativePaths())
