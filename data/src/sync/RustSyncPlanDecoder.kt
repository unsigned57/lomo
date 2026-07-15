package com.lomo.data.sync

import com.lomo.data.repository.RemoteSyncAction
import com.lomo.data.repository.RemoteSyncDirection
import com.lomo.data.repository.RemoteSyncPlan
import com.lomo.data.repository.RemoteSyncReason
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal object RustSyncPlanDecoder {
    fun decode(bytes: ByteArray): RemoteSyncPlan {
        RustSyncValueValidator.payloadSize(bytes.size)
        val reader = RustSyncWireReader(bytes)
        decodeHeader(reader)
        val actions = decodeActions(reader)
        val pendingChanges = reader.u32("pending_changes")
        reader.finish()
        validatePendingCount(actions, pendingChanges)
        return RemoteSyncPlan(actions = actions, pendingChanges = pendingChanges)
    }

    private fun decodeHeader(reader: RustSyncWireReader) {
        if (!reader.bytes(RUST_SYNC_MAGIC.size).contentEquals(RUST_SYNC_MAGIC)) {
            throw RustSyncProtocolException(RustSyncProtocolError.InvalidMagic)
        }
        val actualVersion = reader.u16()
        if (actualVersion != RUST_SYNC_VERSION) {
            throw RustSyncProtocolException(
                RustSyncProtocolError.UnsupportedVersion(actualVersion),
            )
        }
    }

    private fun decodeActions(reader: RustSyncWireReader): List<RemoteSyncAction> {
        val count = reader.u32("actions")
        val paths = HashSet<String>(count)
        return List(count) {
            val path = reader.string("action path")
            RustSyncValueValidator.path(path)
            if (!paths.add(path)) {
                throw RustSyncProtocolException(
                    RustSyncProtocolError.DuplicatePath("actions", path),
                )
            }
            RemoteSyncAction(
                path = path,
                direction = decodeDirection(reader.u8()),
                reason = decodeReason(reader.u8()),
            )
        }
    }

    private fun decodeDirection(value: Int): RemoteSyncDirection =
        RemoteSyncDirection.entries.firstOrNull { it.rustSyncWireValue() == value }
            ?: throw RustSyncProtocolException(
                RustSyncProtocolError.InvalidEnum("direction", value),
            )

    private fun decodeReason(value: Int): RemoteSyncReason =
        RemoteSyncReason.entries.firstOrNull { it.rustSyncWireValue() == value }
            ?: throw RustSyncProtocolException(
                RustSyncProtocolError.InvalidEnum("reason", value),
            )

    private fun validatePendingCount(actions: List<RemoteSyncAction>, pendingChanges: Int) {
        val actualPendingChanges = actions.count { it.direction != RemoteSyncDirection.NONE }
        if (pendingChanges != actualPendingChanges) {
            throw RustSyncProtocolException(
                RustSyncProtocolError.PendingCountMismatch(
                    expected = actualPendingChanges,
                    actual = pendingChanges,
                ),
            )
        }
    }
}

internal class RustSyncWireReader(private val bytes: ByteArray) {
    private var offset = 0

    fun bytes(length: Int): ByteArray {
        if (length < 0 || offset > bytes.size - length) {
            throw RustSyncProtocolException(RustSyncProtocolError.Truncated)
        }
        return bytes.copyOfRange(offset, offset + length).also { offset += length }
    }

    fun u8(): Int = bytes(Byte.SIZE_BYTES).single().toInt() and RUST_SYNC_U8_MAX

    fun u16(): Int {
        val first = u8()
        val second = u8()
        return first or (second shl RUST_SYNC_BYTE_BITS)
    }

    fun u32(field: String): Int {
        val value =
            u8() or
                (u8() shl RUST_SYNC_BYTE_BITS) or
                (u8() shl RUST_SYNC_U32_BYTE_TWO_SHIFT) or
                (u8() shl RUST_SYNC_U32_BYTE_THREE_SHIFT)
        if (value < 0 || value > RUST_SYNC_MAX_ITEMS) {
            throw RustSyncProtocolException(
                RustSyncProtocolError.InvalidCount(field, value.toLong()),
            )
        }
        return value
    }

    fun string(field: String): String {
        val length = u32(field)
        if (length > RUST_SYNC_MAX_STRING_BYTES) {
            throw RustSyncProtocolException(RustSyncProtocolError.InvalidString(field))
        }
        val value = decodeUtf8(bytes(length), field)
        RustSyncValueValidator.string(field, value)
        return value
    }

    fun finish() {
        if (offset != bytes.size) {
            throw RustSyncProtocolException(RustSyncProtocolError.TrailingBytes)
        }
    }

    private fun decodeUtf8(value: ByteArray, field: String): String =
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value))
                .toString()
        } catch (error: CharacterCodingException) {
            throw RustSyncProtocolException(RustSyncProtocolError.InvalidString(field), error)
        }
}
