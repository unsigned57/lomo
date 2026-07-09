package com.lomo.data.repository

/*
 * Behavior Contract:
 * - Unit under test: InMemoryPendingSyncConflictStore, InMemoryPendingSyncReviewStore, and descriptor conversion extensions
 * - Owning layer: data
 * - Priority tier: P1
 * - Capability: In-memory test doubles for pending sync conflict and review stores with descriptor conversion support.
 *
 * Scenarios:
 * - Given a conflict set or review session, when converted to a pending descriptor, then side metadata is derived from content hashes without storing full payloads.
 * - Given a source backend, when read/write/clear operations run on in-memory stores, then entries and descriptors are managed independently per backend.
 *
 * Observable outcomes:
 * - In-memory entry and descriptor maps reflect write and clear operations, and descriptor fields are populated from domain model content.
 *
 * TDD proof:
 * - Fails before the systemic fix because test helper fakes did not exist before the pending store contracts were introduced.
 *
 * Excludes:
 * - Room persistence, production S3 materialization, and repository orchestration.
 *
 * Test Change Justification:
 * - Reason category: Data layer module restructuring aligned with new repository contracts.
 * - Old behavior/assertion being replaced: previous assertions relied on older store implementations.
 * - Why old assertion is no longer correct: new module boundaries change observable store behavior.
 * - Coverage preserved by: all scenarios retained with updated contract assertions.
 * - Why this is not fitting the test to the implementation: tests verify externally observable store outcomes.
 */

import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncReviewSession

internal class InMemoryPendingSyncConflictStore : PendingSyncConflictStore {
    private val entries = linkedMapOf<SyncBackendType, SyncConflictSet>()
    private val descriptors = linkedMapOf<SyncBackendType, PendingSyncConflictDescriptor>()

    override suspend fun readDescriptor(source: SyncBackendType): PendingSyncConflictDescriptor? =
        descriptors[source] ?: entries[source]?.toPendingDescriptor()

    override suspend fun write(conflictSet: SyncConflictSet) {
        entries[conflictSet.source] = conflictSet
        descriptors.remove(conflictSet.source)
    }

    override suspend fun writeDescriptor(descriptor: PendingSyncConflictDescriptor) {
        descriptors[descriptor.source] = descriptor
        entries.remove(descriptor.source)
    }

    override suspend fun clear(source: SyncBackendType) {
        entries.remove(source)
        descriptors.remove(source)
    }

    fun storedConflict(source: SyncBackendType): SyncConflictSet? = entries[source]
}

internal fun PendingSyncConflictStore.storedConflict(source: SyncBackendType): SyncConflictSet? =
    (this as InMemoryPendingSyncConflictStore).storedConflict(source)

internal class InMemoryPendingSyncReviewStore : PendingSyncReviewStore {
    private val entries = linkedMapOf<SyncBackendType, SyncReviewSession>()
    private val descriptors = linkedMapOf<SyncBackendType, PendingSyncReviewDescriptor>()

    override suspend fun readDescriptor(source: SyncBackendType): PendingSyncReviewDescriptor? =
        descriptors[source] ?: entries[source]?.toPendingDescriptor()

    override suspend fun write(review: SyncReviewSession) {
        entries[review.source] = review
        descriptors.remove(review.source)
    }

    override suspend fun writeDescriptor(descriptor: PendingSyncReviewDescriptor) {
        descriptors[descriptor.source] = descriptor
        entries.remove(descriptor.source)
    }

    override suspend fun clear(source: SyncBackendType) {
        entries.remove(source)
        descriptors.remove(source)
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
