package com.lomo.data.sync

import com.lomo.data.repository.RemoteSyncAction
import com.lomo.data.repository.RemoteSyncDirection
import com.lomo.data.repository.RemoteSyncReason
import com.lomo.data.repository.RemoteSyncRemoteAbsenceVerification
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

internal object RustSyncRequestEncoder {
    fun encode(request: RustSyncPlannerRequest): ByteArray {
        RustSyncRequestValidator.validate(request)
        val writer = RustSyncWireWriter()
        writer.bytes(RUST_SYNC_MAGIC)
        writer.u16(RUST_SYNC_VERSION)
        writer.u8(request.backend.wireValue)
        writer.i64(request.timestampToleranceMs)
        encodeLocalFiles(writer, request)
        encodeRemoteFiles(writer, request)
        encodeMetadata(writer, request)
        encodeActions(writer, request.preResolvedActions, "pre_resolved")
        encodeSuppressedPaths(writer, request)
        encodeMissingRemoteVerification(writer, request)
        writer.u8(request.defaultMissingRemoteVerification.rustSyncWireValue())
        return writer.finish()
    }

    private fun encodeLocalFiles(writer: RustSyncWireWriter, request: RustSyncPlannerRequest) {
        writer.u32(request.localFiles.size, "local")
        request.localFiles.forEach { item ->
            writer.string(item.path, "local path")
            writer.i64(item.lastModified)
            writer.optionalI64(item.size)
            writer.optionalString(item.localFingerprint, "local fingerprint")
        }
    }

    private fun encodeRemoteFiles(writer: RustSyncWireWriter, request: RustSyncPlannerRequest) {
        writer.u32(request.remoteFiles.size, "remote")
        request.remoteFiles.forEach { item ->
            writer.string(item.path, "remote path")
            writer.optionalString(item.etag, "remote etag")
            writer.optionalI64(item.lastModified)
            writer.optionalI64(item.size)
            writer.optionalString(item.contentFingerprint, "remote fingerprint")
        }
    }

    private fun encodeMetadata(writer: RustSyncWireWriter, request: RustSyncPlannerRequest) {
        writer.u32(request.metadata.size, "metadata")
        request.metadata.forEach { item ->
            writer.string(item.path, "metadata path")
            writer.optionalString(item.etag, "metadata etag")
            writer.optionalI64(item.remoteLastModified)
            writer.optionalI64(item.localLastModified)
            writer.optionalString(item.localFingerprint, "metadata local_fingerprint")
            writer.i64(item.lastSyncedAt)
        }
    }

    private fun encodeActions(
        writer: RustSyncWireWriter,
        actions: List<RemoteSyncAction>,
        field: String,
    ) {
        writer.u32(actions.size, field)
        actions.forEach { action ->
            writer.string(action.path, "action path")
            writer.u8(action.direction.rustSyncWireValue())
            writer.u8(action.reason.rustSyncWireValue())
        }
    }

    private fun encodeSuppressedPaths(writer: RustSyncWireWriter, request: RustSyncPlannerRequest) {
        writer.u32(request.suppressedPaths.size, "suppressed")
        request.suppressedPaths.forEach { path -> writer.string(path, "suppressed path") }
    }

    private fun encodeMissingRemoteVerification(
        writer: RustSyncWireWriter,
        request: RustSyncPlannerRequest,
    ) {
        writer.u32(request.missingRemoteVerification.size, "missing_remote_verification")
        request.missingRemoteVerification.forEach { item ->
            writer.string(item.path, "missing path")
            writer.u8(item.verification.rustSyncWireValue())
        }
    }
}

internal class RustSyncWireWriter {
    private val output = ByteArrayOutputStream()

    fun bytes(value: ByteArray) {
        output.write(value)
    }

    fun u8(value: Int) {
        require(value in 0..RUST_SYNC_U8_MAX) { "u8 out of range: $value" }
        output.write(value)
    }

    fun u16(value: Int) {
        u8(value and RUST_SYNC_U8_MAX)
        u8((value ushr RUST_SYNC_BYTE_BITS) and RUST_SYNC_U8_MAX)
    }

    fun u32(value: Int, field: String) {
        if (value !in 0..RUST_SYNC_MAX_ITEMS) {
            throw RustSyncProtocolException(
                RustSyncProtocolError.InvalidCount(field, value.toLong()),
            )
        }
        u8(value and RUST_SYNC_U8_MAX)
        u8((value ushr RUST_SYNC_BYTE_BITS) and RUST_SYNC_U8_MAX)
        u8((value ushr RUST_SYNC_U32_BYTE_TWO_SHIFT) and RUST_SYNC_U8_MAX)
        u8((value ushr RUST_SYNC_U32_BYTE_THREE_SHIFT) and RUST_SYNC_U8_MAX)
    }

    fun i64(value: Long) {
        repeat(Long.SIZE_BYTES) { index ->
            u8((value ushr (index * RUST_SYNC_BYTE_BITS)).toInt() and RUST_SYNC_U8_MAX)
        }
    }

    fun string(value: String, field: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > RUST_SYNC_MAX_STRING_BYTES) {
            throw RustSyncProtocolException(RustSyncProtocolError.InvalidString(field))
        }
        u32(bytes.size, field)
        output.write(bytes)
    }

    fun optionalI64(value: Long?) {
        if (value == null) {
            u8(0)
        } else {
            u8(1)
            i64(value)
        }
    }

    fun optionalString(value: String?, field: String) {
        if (value == null) {
            u8(0)
        } else {
            u8(1)
            string(value, field)
        }
    }

    fun finish(): ByteArray =
        output.toByteArray().also { bytes -> RustSyncValueValidator.payloadSize(bytes.size) }
}

internal fun RemoteSyncDirection.rustSyncWireValue(): Int = ordinal

internal fun RemoteSyncReason.rustSyncWireValue(): Int = ordinal

internal fun RemoteSyncRemoteAbsenceVerification.rustSyncWireValue(): Int = ordinal
