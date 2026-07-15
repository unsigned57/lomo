package com.lomo.data.sync

import com.lomo.data.repository.RemoteSyncAction
import com.lomo.data.repository.RemoteSyncLocalSnapshot
import com.lomo.data.repository.RemoteSyncMetadataSnapshot
import com.lomo.data.repository.RemoteSyncRemoteAbsenceVerification
import com.lomo.data.repository.RemoteSyncRemoteSnapshot

internal enum class RustSyncBackend(val wireValue: Int) {
    S3(1),
    WebDav(2),
}

internal data class RustMissingRemoteVerification(
    val path: String,
    val verification: RemoteSyncRemoteAbsenceVerification,
)

internal data class RustSyncPlannerRequest(
    val backend: RustSyncBackend,
    val timestampToleranceMs: Long,
    val localFiles: List<RemoteSyncLocalSnapshot>,
    val remoteFiles: List<RemoteSyncRemoteSnapshot>,
    val metadata: List<RemoteSyncMetadataSnapshot>,
    val preResolvedActions: List<RemoteSyncAction>,
    val suppressedPaths: List<String>,
    val missingRemoteVerification: List<RustMissingRemoteVerification>,
    val defaultMissingRemoteVerification: RemoteSyncRemoteAbsenceVerification,
)

internal sealed interface RustSyncProtocolError {
    data object Truncated : RustSyncProtocolError

    data object InvalidMagic : RustSyncProtocolError

    data object TrailingBytes : RustSyncProtocolError

    data class UnsupportedVersion(val version: Int) : RustSyncProtocolError

    data class InvalidEnum(val field: String, val value: Int) : RustSyncProtocolError

    data class InvalidPath(val path: String) : RustSyncProtocolError

    data class DuplicatePath(val field: String, val path: String) : RustSyncProtocolError

    data class InvalidString(val field: String) : RustSyncProtocolError

    data class InvalidCount(val field: String, val value: Long) : RustSyncProtocolError

    data class NegativeValue(val field: String, val value: Long) : RustSyncProtocolError

    data class PendingCountMismatch(val expected: Int, val actual: Int) : RustSyncProtocolError
}

internal class RustSyncProtocolException(
    val reason: RustSyncProtocolError,
    cause: Throwable? = null,
) : IllegalArgumentException("Rust sync protocol rejected input: $reason", cause)
