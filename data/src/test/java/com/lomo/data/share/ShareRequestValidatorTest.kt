package com.lomo.data.share

import com.lomo.data.share.LomoShareServer.AttachmentInfo
import com.lomo.data.share.LomoShareServer.PrepareRequest
import com.lomo.data.share.LomoShareServer.TransferMetadata
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: ShareRequestValidator
 * - Behavior focus: prepare/transfer payload validation for open vs E2E mode, attachment-name normalization, and nonce consistency.
 * - Observable outcomes: returned rejection message for invalid requests, or null for accepted requests.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: HTTP transport, cryptographic signature verification, and server/client wiring.
 */
@OptIn(ExperimentalEncodingApi::class)
class ShareRequestValidatorTest {
    private val validator = ShareRequestValidator()

    @Test
    fun `validatePrepareRequest accepts open request with trimmed attachment names`() {
        val request =
            prepareRequest(
                e2eEnabled = false,
                attachments =
                    listOf(
                        AttachmentInfo(name = " photo.jpg ", type = "image", size = 12L),
                        AttachmentInfo(name = "voice.m4a", type = "audio", size = 34L),
                    ),
            )

        val result = validator.validatePrepareRequest(request)

        assertNull(result)
    }

    @Test
    fun `validatePrepareRequest rejects duplicate attachment names after trimming`() {
        val request =
            prepareRequest(
                e2eEnabled = false,
                attachments =
                    listOf(
                        AttachmentInfo(name = " photo.jpg ", type = "image", size = 12L),
                        AttachmentInfo(name = "photo.jpg", type = "image", size = 34L),
                    ),
            )

        val result = validator.validatePrepareRequest(request)

        assertEquals("Duplicate attachment name", result)
    }

    @Test
    fun `validatePrepareRequest rejects auth fields in open mode`() {
        val request =
            prepareRequest(
                e2eEnabled = false,
                authTimestampMs = 1L,
                authNonce = "ab12",
                authSignature = VALID_SIGNATURE_HEX,
            )

        val result = validator.validatePrepareRequest(request)

        assertEquals("Auth fields are not allowed in open mode", result)
    }

    @Test
    fun `validateTransferMetadata accepts encrypted metadata with matching attachment nonces`() {
        val metadata =
            transferMetadata(
                e2eEnabled = true,
                attachmentNames = listOf("photo.jpg", "voice.m4a"),
                attachmentNonces =
                    mapOf(
                        "photo.jpg" to validNonce(seed = 2),
                        "voice.m4a" to validNonce(seed = 3),
                    ),
            )

        val result = validator.validateTransferMetadata(metadata)

        assertNull(result)
    }

    @Test
    fun `validateTransferMetadata rejects mismatched attachment nonces in e2e mode`() {
        val metadata =
            transferMetadata(
                e2eEnabled = true,
                attachmentNames = listOf("photo.jpg"),
                attachmentNonces = mapOf("voice.m4a" to validNonce(seed = 2)),
            )

        val result = validator.validateTransferMetadata(metadata)

        assertEquals("Attachment nonce mismatch", result)
    }

    @Test
    fun `validateTransferMetadata rejects invalid attachment nonce payload`() {
        val metadata =
            transferMetadata(
                e2eEnabled = true,
                attachmentNames = listOf("photo.jpg"),
                attachmentNonces = mapOf("photo.jpg" to "not-base64"),
            )

        val result = validator.validateTransferMetadata(metadata)

        assertEquals("Invalid attachment nonce", result)
    }

    private fun prepareRequest(
        e2eEnabled: Boolean,
        attachments: List<AttachmentInfo> = emptyList(),
        authTimestampMs: Long = if (e2eEnabled) 123L else 0L,
        authNonce: String = if (e2eEnabled) "ab12" else "",
        authSignature: String = if (e2eEnabled) VALID_SIGNATURE_HEX else "",
    ): PrepareRequest =
        PrepareRequest(
            senderName = "Sender",
            encryptedContent = "memo-content",
            contentNonce = if (e2eEnabled) validNonce(seed = 1) else "",
            timestamp = 1234L,
            e2eEnabled = e2eEnabled,
            attachments = attachments,
            authTimestampMs = authTimestampMs,
            authNonce = authNonce,
            authSignature = authSignature,
        )

    private fun transferMetadata(
        e2eEnabled: Boolean,
        attachmentNames: List<String>,
        attachmentNonces: Map<String, String>,
    ): TransferMetadata =
        TransferMetadata(
            sessionToken = "session-token",
            encryptedContent = "memo-content",
            contentNonce = if (e2eEnabled) validNonce(seed = 4) else "",
            timestamp = 5678L,
            e2eEnabled = e2eEnabled,
            attachmentNames = attachmentNames,
            attachmentNonces = attachmentNonces,
            authTimestampMs = if (e2eEnabled) 456L else 0L,
            authNonce = if (e2eEnabled) "cd34" else "",
            authSignature = if (e2eEnabled) VALID_SIGNATURE_HEX else "",
        )

    private fun validNonce(seed: Int): String = Base64.Default.encode(ByteArray(12) { seed.toByte() })

    private companion object {
        private const val VALID_SIGNATURE_HEX =
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
    }
}
