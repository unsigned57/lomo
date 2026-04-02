package com.lomo.data.s3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: S3OpenSslCompatCodec
 * - Behavior focus: Remotely Save-compatible OpenSSL PBKDF2/AES-CBC encoding for object keys and content.
 * - Observable outcomes: exact base64url ciphertext for fixed salt, successful round-trip decryption, and wrong-password rejection.
 * - Red phase: Fails before the fix because the codec does not exist, so Remotely Save OpenSSL-compatible S3 encryption is missing.
 * - Excludes: AWS transport, sync planning, metadata persistence, and UI settings.
 */
class S3OpenSslCompatCodecTest {
    private val fixedSalt = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77)

    private val codec = S3OpenSslCompatCodec(saltGenerator = { fixedSalt })

    @Test
    fun `encryptContent matches Remotely Save OpenSSL vector`() {
        val encrypted = codec.encryptContent("hello", password = "secret")

        assertEquals(
            "U2FsdGVkX18AESIzRFVmd-4mfw7uNeT7fSFBJ3eRQ4A",
            encrypted,
        )
        assertEquals("hello", codec.decryptContent(encrypted, password = "secret"))
    }

    @Test
    fun `encryptKey matches Remotely Save OpenSSL vector`() {
        val encrypted = codec.encryptKey("lomo/memo/note.md", password = "secret")

        assertEquals(
            "U2FsdGVkX18AESIzRFVmd0AbejjVVOYKSZY3p7iwasJM5ImV9w40KA0wJ68Th5Su",
            encrypted,
        )
        assertEquals("lomo/memo/note.md", codec.decryptKey(encrypted, password = "secret"))
    }

    @Test
    fun `decryptContent rejects wrong password`() {
        assertThrows(IllegalArgumentException::class.java) {
            codec.decryptContent(
                "U2FsdGVkX18AESIzRFVmd-4mfw7uNeT7fSFBJ3eRQ4A",
                password = "wrong-secret",
            )
        }
    }
}
