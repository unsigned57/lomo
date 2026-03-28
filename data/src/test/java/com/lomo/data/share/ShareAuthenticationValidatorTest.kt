package com.lomo.data.share

import com.lomo.data.share.LomoShareServer.AttachmentInfo
import com.lomo.data.share.LomoShareServer.PrepareRequest
import com.lomo.data.share.LomoShareServer.TransferMetadata
import io.ktor.http.HttpStatusCode
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: ShareAuthenticationValidator
 * - Behavior focus: open-mode auth bypass, pairing-key requirements, signature validation, and replay protection.
 * - Observable outcomes: ShareAuthValidation status/message/keyHex for accepted and rejected requests.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: HTTP request parsing, payload decryption, and UI approval flow.
 */
@OptIn(ExperimentalEncodingApi::class)
class ShareAuthenticationValidatorTest {
    private val keyMaterial = requireNotNull(ShareAuthUtils.deriveKeyMaterialFromPairingCode("654321"))
    private val primaryKeyHex = requireNotNull(resolvePrimaryKeyHex(keyMaterial))

    @Test
    fun `validatePrepareAuthentication bypasses auth in open mode`() {
        val validator = ShareAuthenticationValidator()
        val request =
            PrepareRequest(
                senderName = "Sender",
                encryptedContent = "memo-content",
                contentNonce = "",
                timestamp = 10L,
                e2eEnabled = false,
                attachments = emptyList(),
                authTimestampMs = 0L,
                authNonce = "",
                authSignature = "",
            )

        val result =
            kotlinx.coroutines.runBlocking {
                validator.validatePrepareAuthentication(request) {
                    error("Pairing key should not be resolved for open-mode requests")
                }
            }

        assertTrue(result.ok)
        assertEquals(HttpStatusCode.OK, result.status)
        assertNull(result.keyHex)
    }

    @Test
    fun `validatePrepareAuthentication requires pairing key for e2e requests`() {
        val validator = ShareAuthenticationValidator()
        val request = signedPrepareRequest(keyHex = primaryKeyHex)

        val result =
            kotlinx.coroutines.runBlocking {
                validator.validatePrepareAuthentication(request) { null }
            }

        assertFalse(result.ok)
        assertEquals(HttpStatusCode.PreconditionFailed, result.status)
        assertEquals("LAN share pairing code is not configured on receiver", result.message)
    }

    @Test
    fun `validatePrepareAuthentication rejects invalid signature`() {
        val validator = ShareAuthenticationValidator()
        val request = signedPrepareRequest(keyHex = primaryKeyHex, signatureHex = INVALID_SIGNATURE_HEX)

        val result =
            kotlinx.coroutines.runBlocking {
                validator.validatePrepareAuthentication(request) { keyMaterial }
            }

        assertFalse(result.ok)
        assertEquals(HttpStatusCode.Unauthorized, result.status)
        assertEquals("Invalid auth signature", result.message)
    }

    @Test
    fun `validateTransferAuthentication rejects replayed nonce on second request`() {
        val validator = ShareAuthenticationValidator()
        val metadata = signedTransferMetadata(keyHex = primaryKeyHex, authNonce = "feedface")

        val firstResult =
            kotlinx.coroutines.runBlocking {
                validator.validateTransferAuthentication(metadata) { keyMaterial }
            }
        val secondResult =
            kotlinx.coroutines.runBlocking {
                validator.validateTransferAuthentication(metadata) { keyMaterial }
            }

        assertTrue(firstResult.ok)
        assertEquals(primaryKeyHex, firstResult.keyHex)
        assertFalse(secondResult.ok)
        assertEquals(HttpStatusCode.Forbidden, secondResult.status)
        assertEquals("Replay detected", secondResult.message)
    }

    private fun signedPrepareRequest(
        keyHex: String,
        signatureHex: String? = null,
        authNonce: String = ShareAuthUtils.generateNonce(),
        authTimestampMs: Long = System.currentTimeMillis(),
    ): PrepareRequest {
        val request =
            PrepareRequest(
                senderName = "Sender",
                encryptedContent = "ciphertext",
                contentNonce = validNonce(seed = 1),
                timestamp = 111L,
                e2eEnabled = true,
                attachments = listOf(AttachmentInfo(name = "photo.jpg", type = "image", size = 12L)),
                authTimestampMs = authTimestampMs,
                authNonce = authNonce,
                authSignature = "",
            )
        val payload =
            ShareAuthUtils.buildPreparePayloadToSign(
                senderName = request.senderName,
                encryptedContent = request.encryptedContent,
                contentNonce = request.contentNonce,
                timestamp = request.timestamp,
                attachmentNames = request.attachments.map { it.name },
                authTimestampMs = request.authTimestampMs,
                authNonce = request.authNonce,
            )
        return request.copy(
            authSignature = signatureHex ?: ShareAuthUtils.signPayloadHex(keyHex = keyHex, payload = payload),
        )
    }

    private fun signedTransferMetadata(
        keyHex: String,
        authNonce: String,
        authTimestampMs: Long = System.currentTimeMillis(),
    ): TransferMetadata {
        val metadata =
            TransferMetadata(
                sessionToken = "approved-session",
                encryptedContent = "ciphertext",
                contentNonce = validNonce(seed = 2),
                timestamp = 222L,
                e2eEnabled = true,
                attachmentNames = listOf("photo.jpg"),
                attachmentNonces = mapOf("photo.jpg" to validNonce(seed = 3)),
                authTimestampMs = authTimestampMs,
                authNonce = authNonce,
                authSignature = "",
            )
        val payload =
            ShareAuthUtils.buildTransferPayloadToSign(
                sessionToken = metadata.sessionToken,
                encryptedContent = metadata.encryptedContent,
                contentNonce = metadata.contentNonce,
                timestamp = metadata.timestamp,
                attachmentNames = metadata.attachmentNames,
                authTimestampMs = metadata.authTimestampMs,
                authNonce = metadata.authNonce,
            )
        return metadata.copy(
            authSignature = ShareAuthUtils.signPayloadHex(keyHex = keyHex, payload = payload),
        )
    }

    private fun validNonce(seed: Int): String = Base64.Default.encode(ByteArray(12) { seed.toByte() })

    private companion object {
        private const val INVALID_SIGNATURE_HEX =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
