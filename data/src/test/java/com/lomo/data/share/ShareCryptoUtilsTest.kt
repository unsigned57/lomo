package com.lomo.data.share

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ShareCryptoUtilsTest {
    @Test
    fun `encryptText decryptText roundtrip succeeds`() {
        val keyHex = ShareAuthUtils.deriveKeyHexFromPairingCode("pairing-code-crypto-1")!!
        val encrypted =
            ShareCryptoUtils.encryptText(
                keyHex = keyHex,
                plaintext = "hello lan share",
                aad = "memo-content",
            )

        val decrypted =
            ShareCryptoUtils.decryptText(
                keyHex = keyHex,
                ciphertextBase64 = encrypted.ciphertextBase64,
                nonceBase64 = encrypted.nonceBase64,
                aad = "memo-content",
            )

        assertEquals("hello lan share", decrypted)
    }

    @Test
    fun `decryptText fails with wrong key or aad`() {
        val keyHex = ShareAuthUtils.deriveKeyHexFromPairingCode("pairing-code-crypto-2")!!
        val otherKeyHex = ShareAuthUtils.deriveKeyHexFromPairingCode("pairing-code-crypto-3")!!
        val encrypted =
            ShareCryptoUtils.encryptText(
                keyHex = keyHex,
                plaintext = "secret",
                aad = "memo-content",
            )

        val wrongKeyResult =
            ShareCryptoUtils.decryptText(
                keyHex = otherKeyHex,
                ciphertextBase64 = encrypted.ciphertextBase64,
                nonceBase64 = encrypted.nonceBase64,
                aad = "memo-content",
            )
        val wrongAadResult =
            ShareCryptoUtils.decryptText(
                keyHex = keyHex,
                ciphertextBase64 = encrypted.ciphertextBase64,
                nonceBase64 = encrypted.nonceBase64,
                aad = "other-aad",
            )

        assertNull(wrongKeyResult)
        assertNull(wrongAadResult)
    }

    @Test
    fun `decryptBytes fails when ciphertext is tampered`() {
        val keyHex = ShareAuthUtils.deriveKeyHexFromPairingCode("pairing-code-crypto-4")!!
        val encrypted =
            ShareCryptoUtils.encryptBytes(
                keyHex = keyHex,
                plaintext = byteArrayOf(1, 2, 3, 4, 5),
                aad = "attachment:test.png",
            )
        assertNotNull(encrypted.ciphertext)

        val tampered = encrypted.ciphertext.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()

        val tamperedResult =
            ShareCryptoUtils.decryptBytes(
                keyHex = keyHex,
                ciphertext = tampered,
                nonceBase64 = encrypted.nonceBase64,
                aad = "attachment:test.png",
            )
        val intactResult =
            ShareCryptoUtils.decryptBytes(
                keyHex = keyHex,
                ciphertext = encrypted.ciphertext,
                nonceBase64 = encrypted.nonceBase64,
                aad = "attachment:test.png",
            )

        assertNull(tamperedResult)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), intactResult)
    }
}
