package com.lomo.data.s3

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: S3RcloneCryptCompatCodec
 * - Behavior focus: Remotely Save-compatible rclone filename encoding plus backward-compatible decryption for legacy base32hex names and payloads.
 * - Observable outcomes: exact Remotely Save base64url filename vectors, successful decryption of both base64url and legacy base32hex names, exact content vectors, and tamper rejection.
 * - Red phase: Fails before the fix because Remotely Save base64url rclone object names are rejected as invalid base32hex filenames.
 * - Excludes: AWS transport, sync planning, metadata persistence, and UI rendering.
 */
class S3RcloneCryptCompatCodecTest {
    private val nonce =
        byteArrayOf(
            0x01,
            0x02,
            0x03,
            0x04,
            0x05,
            0x06,
            0x07,
            0x08,
            0x09,
            0x0A,
            0x0B,
            0x0C,
            0x0D,
            0x0E,
            0x0F,
            0x10,
            0x11,
            0x12,
            0x13,
            0x14,
            0x15,
            0x16,
            0x17,
            0x18,
        )

    private val codec =
        S3RcloneCryptCompatCodec(
            nonceGenerator = { nonce.copyOf() },
    )

    @Test
    fun `encryptKey matches remotely save rclone base64url filename vector`() {
        assertEquals(
            "Y3nf7vUZhT99GJAC1Bqipg/85UUhLN5OijcHlZs6pU07A/9vWZAnc9N8y-EfXu9VYmfQ",
            codec.encryptKey("lomo/memo/note.md", password = "secret-pass"),
        )
    }

    @Test
    fun `decryptKey matches official rclone filename vector`() {
        assertEquals(
            "1/12/123",
            codec.decryptKey(
                "p0e52nreeaj0a5ea7s64m4j72s/l42g6771hnv3an9cgc8cr2n1ng/qgm4avr35m5loi1th53ato71v0",
                password = "",
            ),
        )
    }

    @Test
    fun `encryptBytes matches official rclone content vectors`() {
        assertArrayEquals(file0, codec.encryptBytes(byteArrayOf(), password = ""))
        assertArrayEquals(file1, codec.encryptBytes(byteArrayOf(0x01), password = ""))
        assertArrayEquals(
            file16,
            codec.encryptBytes(
                byteArrayOf(
                    0x01,
                    0x02,
                    0x03,
                    0x04,
                    0x05,
                    0x06,
                    0x07,
                    0x08,
                    0x09,
                    0x0A,
                    0x0B,
                    0x0C,
                    0x0D,
                    0x0E,
                    0x0F,
                    0x10,
                ),
                password = "",
            ),
        )
    }

    @Test
    fun `decryptBytes recovers plaintext from official rclone vector`() {
        assertArrayEquals(byteArrayOf(), codec.decryptBytes(file0, password = ""))
        assertArrayEquals(byteArrayOf(0x01), codec.decryptBytes(file1, password = ""))
        assertArrayEquals(
            byteArrayOf(
                0x01,
                0x02,
                0x03,
                0x04,
                0x05,
                0x06,
                0x07,
                0x08,
                0x09,
                0x0A,
                0x0B,
                0x0C,
                0x0D,
                0x0E,
                0x0F,
                0x10,
            ),
            codec.decryptBytes(file16, password = ""),
        )
    }

    @Test
    fun `decryptBytes rejects tampered ciphertext`() {
        val tampered = file16.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 0xFF).toByte() }

        assertThrows(IllegalArgumentException::class.java) {
            codec.decryptBytes(tampered, password = "")
        }
    }

    @Test
    fun `decryptKey matches remotely save rclone base64url filename vector`() {
        assertEquals(
            "lomo/memo/note.md",
            codec.decryptKey(
                "Y3nf7vUZhT99GJAC1Bqipg/85UUhLN5OijcHlZs6pU07A/9vWZAnc9N8y-EfXu9VYmfQ",
                password = "secret-pass",
            ),
        )
    }

    @Test
    fun `decryptKey accepts legacy base32hex filename vector with non-empty password`() {
        assertEquals(
            "lomo/memo/note.md",
            codec.decryptKey(
                "cdstvrnl362juv8oi01d86l2ko/ueah915jf4t2hn0uapmel59ktg/urqpi0jn7krspfghunnfalh6fk",
                password = "secret-pass",
            ),
        )
    }

    @Test
    fun `encryptBytes round-trips with non-empty password`() {
        val plaintext = "hello".toByteArray()

        val encrypted = codec.encryptBytes(plaintext, password = "secret-pass")

        assertArrayEquals(plaintext, codec.decryptBytes(encrypted, password = "secret-pass"))
    }

    @Test
    fun `decryptBytes recovers remotely save rclone crypt vector with non-empty password`() {
        assertArrayEquals(
            "hello".toByteArray(),
            codec.decryptBytes(remotelySaveHelloPayload, password = "secret-pass"),
        )
    }

    private companion object {
        val file0 =
            byteArrayOf(
                0x52,
                0x43,
                0x4C,
                0x4F,
                0x4E,
                0x45,
                0x00,
                0x00,
                0x01,
                0x02,
                0x03,
                0x04,
                0x05,
                0x06,
                0x07,
                0x08,
                0x09,
                0x0A,
                0x0B,
                0x0C,
                0x0D,
                0x0E,
                0x0F,
                0x10,
                0x11,
                0x12,
                0x13,
                0x14,
                0x15,
                0x16,
                0x17,
                0x18,
            )

        val file1 =
            byteArrayOf(
                0x52,
                0x43,
                0x4C,
                0x4F,
                0x4E,
                0x45,
                0x00,
                0x00,
                0x01,
                0x02,
                0x03,
                0x04,
                0x05,
                0x06,
                0x07,
                0x08,
                0x09,
                0x0A,
                0x0B,
                0x0C,
                0x0D,
                0x0E,
                0x0F,
                0x10,
                0x11,
                0x12,
                0x13,
                0x14,
                0x15,
                0x16,
                0x17,
                0x18,
                0x09,
                0x5B,
                0x44,
                0x6C,
                0xD6.toByte(),
                0x23,
                0x7B,
                0xBC.toByte(),
                0xB0.toByte(),
                0x8D.toByte(),
                0x09,
                0xFB.toByte(),
                0x52,
                0x4C,
                0xE5.toByte(),
                0x65,
                0xAA.toByte(),
            )

        val file16 =
            byteArrayOf(
                0x52,
                0x43,
                0x4C,
                0x4F,
                0x4E,
                0x45,
                0x00,
                0x00,
                0x01,
                0x02,
                0x03,
                0x04,
                0x05,
                0x06,
                0x07,
                0x08,
                0x09,
                0x0A,
                0x0B,
                0x0C,
                0x0D,
                0x0E,
                0x0F,
                0x10,
                0x11,
                0x12,
                0x13,
                0x14,
                0x15,
                0x16,
                0x17,
                0x18,
                0xB9.toByte(),
                0xC4.toByte(),
                0x55,
                0x2A,
                0x27,
                0x10,
                0x06,
                0x29,
                0x18,
                0x96.toByte(),
                0x0A,
                0x3E,
                0x60,
                0x8C.toByte(),
                0x29,
                0xB9.toByte(),
                0xAA.toByte(),
                0x8A.toByte(),
                0x5E,
                0x1E,
                0x16,
                0x5B,
                0x6D,
                0x07,
                0x5D,
                0xE4.toByte(),
                0xE9.toByte(),
                0xBB.toByte(),
                0x36,
                0x7F,
                0xD6.toByte(),
                0xD4.toByte(),
            )

        val remotelySaveHelloPayload =
            byteArrayOf(
                0x52,
                0x43,
                0x4C,
                0x4F,
                0x4E,
                0x45,
                0x00,
                0x00,
                0x18,
                0x5A,
                0xF3.toByte(),
                0x75,
                0xBC.toByte(),
                0xBD.toByte(),
                0x38,
                0x37,
                0xD0.toByte(),
                0x33,
                0xCD.toByte(),
                0x92.toByte(),
                0xB4.toByte(),
                0x8B.toByte(),
                0x67,
                0x77,
                0xF8.toByte(),
                0x5B,
                0x40,
                0xD9.toByte(),
                0x65,
                0x3D,
                0xAD.toByte(),
                0xCA.toByte(),
                0xF9.toByte(),
                0xA0.toByte(),
                0x5E,
                0x56,
                0xCB.toByte(),
                0xD7.toByte(),
                0xBB.toByte(),
                0x85.toByte(),
                0xC8.toByte(),
                0xB2.toByte(),
                0xBA.toByte(),
                0xAF.toByte(),
                0x25,
                0x1D,
                0xFF.toByte(),
                0xC1.toByte(),
                0xDB.toByte(),
                0x9B.toByte(),
                0x09,
                0xB0.toByte(),
                0x3A,
            )
    }
}
