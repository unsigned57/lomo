package com.lomo.data.repository

import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncReviewItem
import com.lomo.domain.model.SyncReviewSession

sealed interface PendingSyncRestoreResult<out T> {
    data class Restored<T>(
        val session: T,
    ) : PendingSyncRestoreResult<T>

    data class Invalidated(
        val reason: PendingSyncInvalidationReason,
    ) : PendingSyncRestoreResult<Nothing>

    data class Failed(
        val error: PendingSyncRestoreError,
    ) : PendingSyncRestoreResult<Nothing>
}

enum class PendingSyncInvalidationReason {
    STALE_LOCAL,
    STALE_REMOTE,
    MISSING_LOCAL,
    MISSING_REMOTE,
    UNSUPPORTED_BACKEND,
}

data class PendingSyncRestoreError(
    val category: PendingSyncRestoreErrorCategory,
    val message: String,
    val cause: Throwable? = null,
)

enum class PendingSyncRestoreErrorCategory {
    CREDENTIAL_FAILED,
    BUDGET_EXHAUSTED,
    LOCAL_IO_FAILED,
    REMOTE_IO_FAILED,
    METADATA_FAILED,
    SERIALIZATION_FAILED,
    CONTRACT_VIOLATION,
    UNKNOWN,
}

fun interface PendingSyncConflictRestorer {
    suspend fun restore(descriptor: PendingSyncConflictDescriptor): PendingSyncRestoreResult<SyncConflictSet>
}

fun interface PendingSyncReviewRestorer {
    suspend fun restore(descriptor: PendingSyncReviewDescriptor): PendingSyncRestoreResult<SyncReviewSession>
}

internal fun PendingSyncSideMetadata.matchesLocal(local: LocalS3File): Boolean =
    matchesMtimeAndSize(
        actualLastModified = local.lastModified,
        actualSize = local.size,
    )

internal fun PendingSyncSideMetadata.matchesLocal(local: LocalWebDavFile): Boolean =
    matchesMtimeAndSize(
        actualLastModified = local.lastModified,
        actualSize = local.size,
    ) &&
        contentHash != null &&
        contentHash == local.localFingerprint

internal fun PendingSyncSideMetadata.matchesRemote(
    actualEtag: String?,
    actualLastModified: Long?,
    actualSize: Long?,
): Boolean =
    if (etag == null || lastModified == null || size == null) {
        false
    } else {
        etag == actualEtag &&
            lastModified == actualLastModified &&
            size == actualSize
    }

internal fun PendingSyncSideMetadata.matchesContent(content: String?): Boolean =
    if (contentHash == null) {
        false
    } else {
        content?.toByteArray(Charsets.UTF_8)?.md5Hex() == contentHash
    }

private fun PendingSyncSideMetadata.matchesMtimeAndSize(
    actualLastModified: Long?,
    actualSize: Long?,
): Boolean =
    if (lastModified == null || size == null) {
        false
    } else {
        lastModified == actualLastModified && size == actualSize
    }

internal fun PendingSyncConflictDescriptor.invalidatedConflictSet(): SyncConflictSet =
    SyncConflictSet(
        source = source,
        files =
            files.map { file ->
                SyncConflictFile(
                    relativePath = file.relativePath,
                    localContent = null,
                    remoteContent = null,
                    isBinary = file.isBinary,
                    localLastModified = file.local.lastModified,
                    remoteLastModified = file.remote.lastModified,
                )
            },
        timestamp = timestamp,
    )

internal fun PendingSyncReviewDescriptor.invalidatedReviewSession(
    reason: PendingSyncInvalidationReason,
): SyncReviewSession =
    SyncReviewSession(
        source = source,
        items =
            items.map { item ->
                SyncReviewItem(
                    relativePath = item.relativePath,
                    localContent = null,
                    incomingContent = null,
                    isBinary = item.isBinary,
                    localLastModified = item.local.lastModified,
                    incomingLastModified = item.incoming.lastModified,
                    state = item.state,
                    message = item.message ?: "Pending session invalidated: $reason",
                )
            },
        timestamp = timestamp,
        kind = kind,
    )
