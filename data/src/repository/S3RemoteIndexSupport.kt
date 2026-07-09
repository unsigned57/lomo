package com.lomo.data.repository

import com.lomo.data.local.dao.S3RemoteIndexDao
import com.lomo.data.local.entity.S3RemoteIndexEntity
import com.lomo.domain.repository.WorkspaceSyncGenerationProvider


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
    val contentMd5: String? = null,
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

class RoomBackedS3RemoteIndexStore(
    private val dao: S3RemoteIndexDao,
    private val generationProvider: WorkspaceSyncGenerationProvider,
) : S3RemoteIndexStore {
        override val remoteIndexEnabled: Boolean = true

        override suspend fun readAllRelativePaths(): List<String> = dao.getAllRelativePaths(activeGeneration())

        override suspend fun readPresentCount(): Int = dao.getPresentCount(activeGeneration())

        override suspend fun readByRelativePaths(relativePaths: Collection<String>): List<S3RemoteIndexEntry> =
            if (relativePaths.isEmpty()) {
                emptyList()
            } else {
                dao.getByRelativePaths(relativePaths.toList(), activeGeneration()).map(S3RemoteIndexEntity::toModel)
            }

        override suspend fun readByRelativePrefix(relativePrefix: String?): List<S3RemoteIndexEntry> {
            val normalizedPrefix = relativePrefix?.trim()?.trim('/')?.takeIf(String::isNotBlank)
            val generation = activeGeneration()
            return if (normalizedPrefix == null) {
                dao.getAll(generation).map(S3RemoteIndexEntity::toModel)
            } else {
                dao
                    .getByRelativePrefix(
                        relativePrefix = normalizedPrefix,
                        descendantPattern = "$normalizedPrefix/%",
                        workspaceGeneration = generation,
                    ).map(S3RemoteIndexEntity::toModel)
            }
        }

        override suspend fun readOutsideScanBuckets(excludedBuckets: Collection<String>): List<S3RemoteIndexEntry> {
            val generation = activeGeneration()
            return if (excludedBuckets.isEmpty()) {
                dao.getAll(generation).map(S3RemoteIndexEntity::toModel)
            } else {
                dao.getOutsideScanBuckets(excludedBuckets.toList(), generation).map(S3RemoteIndexEntity::toModel)
            }
        }

        override suspend fun readReconcileCandidates(limit: Int): List<S3RemoteIndexEntry> =
            dao
                .getReconcileCandidates(limit = limit, workspaceGeneration = activeGeneration())
                .map(S3RemoteIndexEntity::toModel)

        override suspend fun upsert(entries: Collection<S3RemoteIndexEntry>) {
            if (entries.isEmpty()) return
            val generation = activeGeneration()
            dao.upsertAll(entries.map { entry -> entry.toEntity(generation) })
        }

        override suspend fun deleteByRelativePaths(relativePaths: Collection<String>) {
            if (relativePaths.isEmpty()) return
            dao.deleteByRelativePaths(relativePaths.toList(), activeGeneration())
        }

        override suspend fun deleteOutsideScanEpoch(scanEpoch: Long) {
            dao.deleteOutsideScanEpoch(scanEpoch = scanEpoch, workspaceGeneration = activeGeneration())
        }

        override suspend fun replaceAll(entries: Collection<S3RemoteIndexEntry>) {
            val generation = activeGeneration()
            dao.clearAll(generation)
            if (entries.isNotEmpty()) {
                dao.upsertAll(entries.map { entry -> entry.toEntity(generation) })
            }
        }

        override suspend fun clear() {
            dao.clearAll(activeGeneration())
        }

        private suspend fun activeGeneration(): String = generationProvider.activeGeneration().value
    }


private fun S3RemoteIndexEntity.toModel(): S3RemoteIndexEntry =
    S3RemoteIndexEntry(
        relativePath = relativePath,
        remotePath = remotePath,
        etag = etag,
        remoteLastModified = remoteLastModified,
        size = size,
        contentMd5 = contentMd5,
        lastSeenAt = lastSeenAt,
        lastVerifiedAt = lastVerifiedAt,
        scanBucket = scanBucket,
        scanPriority = scanPriority,
        dirtySuspect = dirtySuspect,
        missingOnLastScan = missingOnLastScan,
        scanEpoch = scanEpoch,
    )

private fun S3RemoteIndexEntry.toEntity(workspaceGeneration: String): S3RemoteIndexEntity =
    S3RemoteIndexEntity(
        workspaceGeneration = workspaceGeneration,
        relativePath = relativePath,
        remotePath = remotePath,
        etag = etag,
        remoteLastModified = remoteLastModified,
        size = size,
        contentMd5 = contentMd5,
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
