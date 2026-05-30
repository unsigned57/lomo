package com.lomo.data.repository

import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncReviewSession

internal class InMemoryPendingSyncConflictStore : PendingSyncConflictStore {
    private val entries = linkedMapOf<SyncBackendType, SyncConflictSet>()

    override suspend fun readDescriptor(source: SyncBackendType): PendingSyncConflictDescriptor? =
        entries[source]?.toPendingDescriptor()

    override suspend fun write(conflictSet: SyncConflictSet) {
        entries[conflictSet.source] = conflictSet
    }

    override suspend fun clear(source: SyncBackendType) {
        entries.remove(source)
    }

    fun storedConflict(source: SyncBackendType): SyncConflictSet? = entries[source]
}

internal fun PendingSyncConflictStore.storedConflict(source: SyncBackendType): SyncConflictSet? =
    (this as InMemoryPendingSyncConflictStore).storedConflict(source)

internal class InMemoryPendingSyncReviewStore : PendingSyncReviewStore {
    private val entries = linkedMapOf<SyncBackendType, SyncReviewSession>()

    override suspend fun readDescriptor(source: SyncBackendType): PendingSyncReviewDescriptor? =
        entries[source]?.toPendingDescriptor()

    override suspend fun write(review: SyncReviewSession) {
        entries[review.source] = review
    }

    override suspend fun clear(source: SyncBackendType) {
        entries.remove(source)
    }

    fun storedReview(source: SyncBackendType): SyncReviewSession? = entries[source]
}

internal fun PendingSyncReviewStore.storedReview(source: SyncBackendType): SyncReviewSession? =
    (this as InMemoryPendingSyncReviewStore).storedReview(source)

internal fun SyncConflictSet.toPendingDescriptor(
    workspaceGeneration: String = "test",
): PendingSyncConflictDescriptor =
    PendingSyncConflictDescriptor(
        source = source,
        workspaceGeneration = workspaceGeneration,
        files =
            files.map { file ->
                PendingSyncConflictFileDescriptor(
                    relativePath = file.relativePath,
                    isBinary = file.isBinary,
                    local =
                        PendingSyncSideMetadata(
                            locator = file.relativePath,
                            contentHash = file.localContent?.toByteArray(Charsets.UTF_8)?.md5Hex(),
                            lastModified = file.localLastModified,
                        ),
                    remote =
                        PendingSyncSideMetadata(
                            locator = file.relativePath,
                            contentHash = file.remoteContent?.toByteArray(Charsets.UTF_8)?.md5Hex(),
                            lastModified = file.remoteLastModified,
                        ),
                )
            },
        timestamp = timestamp,
        validationStatus = PendingSyncValidationStatus.PENDING_RELOAD,
    )

internal fun SyncReviewSession.toPendingDescriptor(
    workspaceGeneration: String = "test",
): PendingSyncReviewDescriptor =
    PendingSyncReviewDescriptor(
        source = source,
        workspaceGeneration = workspaceGeneration,
        kind = kind,
        items =
            items.map { item ->
                PendingSyncReviewItemDescriptor(
                    relativePath = item.relativePath,
                    isBinary = item.isBinary,
                    local =
                        PendingSyncSideMetadata(
                            locator = item.relativePath,
                            contentHash = item.localContent?.toByteArray(Charsets.UTF_8)?.md5Hex(),
                            lastModified = item.localLastModified,
                            size = item.localContent?.toByteArray(Charsets.UTF_8)?.size?.toLong(),
                            etag = item.localContent?.toByteArray(Charsets.UTF_8)?.md5Hex(),
                        ),
                    incoming =
                        PendingSyncSideMetadata(
                            locator = item.relativePath,
                            contentHash = item.incomingContent?.toByteArray(Charsets.UTF_8)?.md5Hex(),
                            lastModified = item.incomingLastModified,
                            size = item.incomingContent?.toByteArray(Charsets.UTF_8)?.size?.toLong(),
                            etag = item.incomingContent?.toByteArray(Charsets.UTF_8)?.md5Hex(),
                        ),
                    state = item.state,
                    message = item.message,
                )
            },
        timestamp = timestamp,
        validationStatus = PendingSyncValidationStatus.PENDING_RELOAD,
    )
