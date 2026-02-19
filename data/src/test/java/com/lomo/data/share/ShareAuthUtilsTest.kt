package com.lomo.data.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareAuthUtilsTest {
    @Test
    fun `deriveKeyHexFromPairingCode returns stable 64-char hex`() {
        val key1 = ShareAuthUtils.deriveKeyHexFromPairingCode("shared-secret-123")
        val key2 = ShareAuthUtils.deriveKeyHexFromPairingCode("shared-secret-123")

        assertNotNull(key1)
        assertEquals(key1, key2)
        assertTrue(key1!!.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun `deriveKeyHexFromPairingCode rejects invalid lengths`() {
        assertNull(ShareAuthUtils.deriveKeyHexFromPairingCode("short"))
        assertNull(ShareAuthUtils.deriveKeyHexFromPairingCode("x".repeat(65)))
    }

    @Test
    fun `verifySignature succeeds for matching payload and fails for tampered payload`() {
        val keyHex = ShareAuthUtils.deriveKeyHexFromPairingCode("pairing-code-001")!!
        val payload =
            ShareAuthUtils.buildPreparePayloadToSign(
                senderName = "Pixel",
                encryptedContent = "ciphertextA==",
                contentNonce = "nonceA==",
                timestamp = 1234L,
                attachmentNames = listOf("b.png", "a.png"),
                authTimestampMs = 5000L,
                authNonce = "aabbccddeeff0011",
            )
        val signature = ShareAuthUtils.signPayloadHex(keyHex, payload)

        assertTrue(ShareAuthUtils.verifySignature(keyHex, payload, signature))
        assertFalse(ShareAuthUtils.verifySignature(keyHex, "$payload-tampered", signature))
    }

    @Test
    fun `payload canonicalization ignores attachment order`() {
        val payloadA =
            ShareAuthUtils.buildTransferPayloadToSign(
                sessionToken = "token",
                encryptedContent = "ciphertextA==",
                contentNonce = "nonceA==",
                timestamp = 10L,
                attachmentNames = listOf("b.png", "a.png"),
                authTimestampMs = 20L,
                authNonce = "1122334455667788",
            )
        val payloadB =
            ShareAuthUtils.buildTransferPayloadToSign(
                sessionToken = "token",
                encryptedContent = "ciphertextA==",
                contentNonce = "nonceA==",
                timestamp = 10L,
                attachmentNames = listOf("a.png", "b.png"),
                authTimestampMs = 20L,
                authNonce = "1122334455667788",
            )

        assertEquals(payloadA, payloadB)
    }

    @Test
    fun `timestamp window check behaves as expected`() {
        assertTrue(ShareAuthUtils.isTimestampWithinWindow(1_000L, nowMs = 1_500L, windowMs = 1_000L))
        assertFalse(ShareAuthUtils.isTimestampWithinWindow(1_000L, nowMs = 2_500L, windowMs = 1_000L))
    }
}
