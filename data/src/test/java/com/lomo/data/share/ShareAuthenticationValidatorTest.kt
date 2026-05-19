package com.lomo.data.share

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */



import com.lomo.data.share.LomoShareServer.AttachmentInfo
import com.lomo.data.share.LomoShareServer.PrepareRequest
import com.lomo.data.share.LomoShareServer.TransferMetadata
import io.ktor.http.HttpStatusCode
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.nulls.shouldBeNull

/*
 * Behavior Contract:
 * - Unit under test: ShareAuthenticationValidator
 * - Behavior focus: open-mode auth bypass, pairing-key requirements, signature validation, and replay protection.
 * - Observable outcomes: ShareAuthValidation status/message/keyHex for accepted and rejected requests.
 * - TDD proof: Fails before the fix because open-mode prepare authentication still short-circuits
 *   on `!e2eEnabled`, allowing unsigned requests without a configured pairing key.
 * - Excludes: HTTP request parsing, payload decryption, and UI approval flow.
 */
@OptIn(ExperimentalEncodingApi::class)
class ShareAuthenticationValidatorTest : DataFunSpec() {
    init {
        test("validatePrepareAuthentication requires pairing key in open mode") { `validatePrepareAuthentication requires pairing key in open mode`() }

        test("validatePrepareAuthentication accepts valid signed open mode request") { `validatePrepareAuthentication accepts valid signed open mode request`() }

        test("validatePrepareAuthentication requires pairing key for e2e requests") { `validatePrepareAuthentication requires pairing key for e2e requests`() }

        test("validatePrepareAuthentication rejects invalid signature") { `validatePrepareAuthentication rejects invalid signature`() }

        test("validateTransferAuthentication rejects replayed nonce on second request") { `validateTransferAuthentication rejects replayed nonce on second request`() }
    }


    private val keyMaterial = requireNotNull(ShareAuthUtils.deriveKeyMaterialFromPairingCode("654321"))
    private val primaryKeyHex = requireNotNull(resolvePrimaryKeyHex(keyMaterial))

    private fun `validatePrepareAuthentication requires pairing key in open mode`() {
        val validator = ShareAuthenticationValidator()
        val request =
            signedPrepareRequest(
                keyHex = primaryKeyHex,
                e2eEnabled = false,
                encryptedContent = "memo-content",
                contentNonce = "",
            )

        val result =
            kotlinx.coroutines.runBlocking {
                validator.validatePrepareAuthentication(request) {
                    null
                }
            }

        (result.ok).shouldBeFalse()
        result.status shouldBe HttpStatusCode.PreconditionFailed
        result.message shouldBe "LAN share pairing code is not configured on receiver"
        result.keyHex.shouldBeNull()
    }

    private fun `validatePrepareAuthentication accepts valid signed open mode request`() {
        val validator = ShareAuthenticationValidator()
        val request =
            signedPrepareRequest(
                keyHex = primaryKeyHex,
                e2eEnabled = false,
                encryptedContent = "memo-content",
                contentNonce = "",
            )

        val result =
            kotlinx.coroutines.runBlocking {
                validator.validatePrepareAuthentication(request) { keyMaterial }
            }

        (result.ok).shouldBeTrue()
        result.status shouldBe HttpStatusCode.OK
        result.keyHex.shouldBeNull()
    }

    private fun `validatePrepareAuthentication requires pairing key for e2e requests`() {
        val validator = ShareAuthenticationValidator()
        val request = signedPrepareRequest(keyHex = primaryKeyHex)

        val result =
            kotlinx.coroutines.runBlocking {
                validator.validatePrepareAuthentication(request) { null }
            }

        (result.ok).shouldBeFalse()
        result.status shouldBe HttpStatusCode.PreconditionFailed
        result.message shouldBe "LAN share pairing code is not configured on receiver"
    }

    private fun `validatePrepareAuthentication rejects invalid signature`() {
        val validator = ShareAuthenticationValidator()
        val request = signedPrepareRequest(keyHex = primaryKeyHex, signatureHex = INVALID_SIGNATURE_HEX)

        val result =
            kotlinx.coroutines.runBlocking {
                validator.validatePrepareAuthentication(request) { keyMaterial }
            }

        (result.ok).shouldBeFalse()
        result.status shouldBe HttpStatusCode.Unauthorized
        result.message shouldBe "Invalid auth signature"
    }

    private fun `validateTransferAuthentication rejects replayed nonce on second request`() {
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

        (firstResult.ok).shouldBeTrue()
        firstResult.keyHex shouldBe primaryKeyHex
        (secondResult.ok).shouldBeFalse()
        secondResult.status shouldBe HttpStatusCode.Forbidden
        secondResult.message shouldBe "Replay detected"
    }

    private fun signedPrepareRequest(
        keyHex: String,
        e2eEnabled: Boolean = true,
        encryptedContent: String = "ciphertext",
        contentNonce: String = validNonce(seed = 1),
        signatureHex: String? = null,
        authNonce: String = ShareAuthUtils.generateNonce(),
        authTimestampMs: Long = System.currentTimeMillis(),
    ): PrepareRequest {
        val request =
            PrepareRequest(
                senderName = "Sender",
                encryptedContent = encryptedContent,
                contentNonce = contentNonce,
                timestamp = 111L,
                e2eEnabled = e2eEnabled,
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
