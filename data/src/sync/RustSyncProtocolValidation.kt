package com.lomo.data.sync

import com.lomo.data.repository.RemoteSyncAction
import com.lomo.data.repository.RemoteSyncLocalSnapshot
import com.lomo.data.repository.RemoteSyncMetadataSnapshot
import com.lomo.data.repository.RemoteSyncRemoteSnapshot
import java.nio.charset.StandardCharsets

internal object RustSyncRequestValidator {
    fun validate(request: RustSyncPlannerRequest) {
        validateTimestampTolerance(request.timestampToleranceMs)
        validateUniquePaths("local", request.localFiles.map(RemoteSyncLocalSnapshot::path))
        validateUniquePaths("remote", request.remoteFiles.map(RemoteSyncRemoteSnapshot::path))
        validateUniquePaths("metadata", request.metadata.map(RemoteSyncMetadataSnapshot::path))
        validateUniquePaths("pre_resolved", request.preResolvedActions.map(RemoteSyncAction::path))
        validateUniquePaths("suppressed", request.suppressedPaths)
        validateUniquePaths(
            "missing_remote_verification",
            request.missingRemoteVerification.map(RustMissingRemoteVerification::path),
        )
        validateLocalFiles(request.localFiles)
        validateRemoteFiles(request.remoteFiles)
        validateMetadata(request.metadata)
        request.preResolvedActions.forEach { action -> RustSyncValueValidator.path(action.path) }
        request.suppressedPaths.forEach(RustSyncValueValidator::path)
        request.missingRemoteVerification.forEach { item ->
            RustSyncValueValidator.path(item.path)
        }
    }

    private fun validateTimestampTolerance(value: Long) {
        if (value < 0L) {
            throw RustSyncProtocolException(
                RustSyncProtocolError.NegativeValue("timestamp_tolerance_ms", value),
            )
        }
    }

    private fun validateUniquePaths(field: String, paths: List<String>) {
        val seen = HashSet<String>(paths.size)
        paths.forEach { path ->
            if (!seen.add(path)) {
                throw RustSyncProtocolException(RustSyncProtocolError.DuplicatePath(field, path))
            }
        }
    }

    private fun validateLocalFiles(files: List<RemoteSyncLocalSnapshot>) {
        files.forEach { item ->
            RustSyncValueValidator.path(item.path)
            RustSyncValueValidator.nonNegative("local size", item.size)
            RustSyncValueValidator.string("local fingerprint", item.localFingerprint)
        }
    }

    private fun validateRemoteFiles(files: List<RemoteSyncRemoteSnapshot>) {
        files.forEach { item ->
            RustSyncValueValidator.path(item.path)
            RustSyncValueValidator.nonNegative("remote size", item.size)
            RustSyncValueValidator.string("remote etag", item.etag)
            RustSyncValueValidator.string("remote fingerprint", item.contentFingerprint)
        }
    }

    private fun validateMetadata(metadata: List<RemoteSyncMetadataSnapshot>) {
        metadata.forEach { item ->
            RustSyncValueValidator.path(item.path)
            RustSyncValueValidator.string("metadata etag", item.etag)
            RustSyncValueValidator.string("metadata fingerprint", item.localFingerprint)
        }
    }
}

internal object RustSyncValueValidator {
    fun path(path: String) {
        if (path.isInvalidRustSyncPath()) {
            throw RustSyncProtocolException(RustSyncProtocolError.InvalidPath(path))
        }
        string("path", path)
    }

    fun string(field: String, value: String?) {
        if (value != null && value.isInvalidRustSyncString()) {
            throw RustSyncProtocolException(RustSyncProtocolError.InvalidString(field))
        }
    }

    fun nonNegative(field: String, value: Long?) {
        if (value != null && value < 0L) {
            throw RustSyncProtocolException(RustSyncProtocolError.NegativeValue(field, value))
        }
    }

    fun payloadSize(size: Int) {
        if (size > RUST_SYNC_MAX_PAYLOAD_BYTES) {
            throw RustSyncProtocolException(
                RustSyncProtocolError.InvalidCount("payload", size.toLong()),
            )
        }
    }

    private fun String.isInvalidRustSyncPath(): Boolean =
        isEmpty() || hasInvalidRustSyncPrefix() || contains(NULL_CHARACTER) || hasInvalidPathSegment()

    private fun String.hasInvalidRustSyncPrefix(): Boolean = startsWith('/') || startsWith('\\')

    private fun String.hasInvalidPathSegment(): Boolean =
        split('/').any { segment -> segment.isEmpty() || segment == CURRENT_PATH || segment == PARENT_PATH }

    private fun String.isInvalidRustSyncString(): Boolean =
        toByteArray(StandardCharsets.UTF_8).size > RUST_SYNC_MAX_STRING_BYTES || contains(NULL_CHARACTER)
}

internal const val RUST_SYNC_VERSION = 1
internal const val RUST_SYNC_MAX_ITEMS = 1_000_000
internal const val RUST_SYNC_MAX_STRING_BYTES = 1024 * 1024
internal const val RUST_SYNC_MAX_PAYLOAD_BYTES = 64 * 1024 * 1024
internal const val RUST_SYNC_U8_MAX = 0xff
internal const val RUST_SYNC_BYTE_BITS = 8
internal const val RUST_SYNC_U32_BYTE_TWO_SHIFT = RUST_SYNC_BYTE_BITS * 2
internal const val RUST_SYNC_U32_BYTE_THREE_SHIFT = RUST_SYNC_BYTE_BITS * 3
internal const val NULL_CHARACTER = '\u0000'
internal const val CURRENT_PATH = "."
internal const val PARENT_PATH = ".."

internal val RUST_SYNC_MAGIC = byteArrayOf(0x4c, 0x4f, 0x4d, 0x4f)
