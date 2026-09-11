package com.lomo.data.repository

import com.lomo.domain.model.SyncConflictSet
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
    MISSING_LOCAL,
    MISSING_REMOTE,
    STALE_LOCAL,
    STALE_REMOTE,
    WORKSPACE_CHANGED,
    CORRUPT_DESCRIPTOR,
}

data class PendingSyncRestoreError(
    val message: String,
    val cause: Throwable? = null,
    val category: String? = null,
)

/** Conflict restore port retained as type shell; production conflicts live in Sync Center / Rust. */
interface PendingSyncConflictSessionRestorer {
    suspend fun restore(descriptor: PendingSyncConflictDescriptor): PendingSyncRestoreResult<SyncConflictSet>
}

interface PendingSyncReviewSessionRestorer {
    suspend fun restore(descriptor: PendingSyncReviewDescriptor): PendingSyncRestoreResult<SyncReviewSession>
}

internal fun PendingSyncSideMetadata.matchesRemote(
    actualEtag: String?,
    actualLastModified: Long? = null,
    actualSize: Long? = null,
): Boolean {
    if (etag != null && actualEtag != null && etag != actualEtag) return false
    if (lastModified != null && actualLastModified != null && lastModified != actualLastModified) return false
    if (size != null && actualSize != null && size != actualSize) return false
    return true
}

internal fun PendingSyncSideMetadata.hasCompleteRemoteMetadata(): Boolean =
    etag != null || (lastModified != null && size != null)

internal fun PendingSyncSideMetadata.matchesContent(content: String?): Boolean =
    contentHash == null ||
        content?.toByteArray(Charsets.UTF_8)?.md5Hex() == contentHash
