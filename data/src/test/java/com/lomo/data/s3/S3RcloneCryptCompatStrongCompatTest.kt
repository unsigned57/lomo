package com.lomo.data.s3

import com.lomo.domain.model.S3RcloneCryptConfig
import com.lomo.domain.model.S3RcloneFilenameEncoding
import com.lomo.domain.model.S3RcloneFilenameEncryption
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: S3RcloneCryptCompatCodec
 * - Behavior focus: strong rclone crypt compatibility across base32768, obfuscate, filename-off suffix handling, directory-name passthrough, and password2-derived keys.
 * - Observable outcomes: exact rclone v1.73.3 filename vectors, successful decryption of password2 ciphertext, and reversible suffix-based filename decoding.
 * - Red phase: Fails before the fix because the codec only supports standard filename encryption with base64/base32 and has no password2, base32768, obfuscate, or filename-off behavior.
 * - Excludes: AWS transport, sync orchestration, and settings UI.
 */
class S3RcloneCryptCompatStrongCompatTest {
    private val codec =
        S3RcloneCryptCompatCodec(
            nonceGenerator = {
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
            },
        )

    private val standardConfig =
        S3RcloneCryptConfig(
            filenameEncryption = S3RcloneFilenameEncryption.STANDARD,
            directoryNameEncryption = true,
            filenameEncoding = S3RcloneFilenameEncoding.BASE64,
            dataEncryptionEnabled = true,
            encryptedSuffix = ".bin",
        )

    @Test
    fun `standard base32768 filename vector matches rclone`() {
        val config = standardConfig.copy(filenameEncoding = S3RcloneFilenameEncoding.BASE32768)

        val encrypted =
            codec.encryptKey(
                key = "lomo/memo/note.md",
                password = "secret-pass",
                password2 = "",
                config = config,
            )

        assertEquals("堜鹛蔃㺓ꊈ袠ᦈ䃢租/ꁪ殁㲯㧢浀龹耵㭴鳟/ꈚ負畇秜豐渷葊粆敟", encrypted)
        assertEquals(
            "lomo/memo/note.md",
            codec.decryptKey(
                encryptedKey = encrypted,
                password = "secret-pass",
                password2 = "",
                config = config,
            ),
        )
    }

    @Test
    fun `standard filename encryption can keep directories plaintext`() {
        val config = standardConfig.copy(directoryNameEncryption = false)

        val encrypted =
            codec.encryptKey(
                key = "attachments/screenshots/shot.png",
                password = "secret-pass",
                password2 = "",
                config = config,
            )

        assertEquals("attachments/screenshots/WYGqdZq0CGWonT5SwE77fw", encrypted)
        assertEquals(
            "attachments/screenshots/shot.png",
            codec.decryptKey(
                encryptedKey = encrypted,
                password = "secret-pass",
                password2 = "",
                config = config,
            ),
        )
    }

    @Test
    fun `obfuscate filename vector matches rclone`() {
        val config = standardConfig.copy(filenameEncryption = S3RcloneFilenameEncryption.OBFUSCATE)

        val encrypted =
            codec.encryptKey(
                key = "lomo/memo/note.md",
                password = "secret-pass",
                password2 = "",
                config = config,
            )

        assertEquals("183.BECE/174.tltv/181.BCHs.Ar", encrypted)
        assertEquals(
            "lomo/memo/note.md",
            codec.decryptKey(
                encryptedKey = encrypted,
                password = "secret-pass",
                password2 = "",
                config = config,
            ),
        )
    }

    @Test
    fun `filename encryption off appends suffix and decodes back`() {
        val config = standardConfig.copy(filenameEncryption = S3RcloneFilenameEncryption.OFF)

        val encrypted =
            codec.encryptKey(
                key = "lomo/memo/note.md",
                password = "secret-pass",
                password2 = "",
                config = config,
            )

        assertEquals("lomo/memo/note.md.bin", encrypted)
        assertEquals(
            "lomo/memo/note.md",
            codec.decryptKey(
                encryptedKey = encrypted,
                password = "secret-pass",
                password2 = "",
                config = config,
            ),
        )
    }

    @Test
    fun `filename encryption off supports suffix none`() {
        val config =
            standardConfig.copy(
                filenameEncryption = S3RcloneFilenameEncryption.OFF,
                encryptedSuffix = "",
            )

        val encrypted =
            codec.encryptKey(
                key = "lomo/memo/note.md",
                password = "secret-pass",
                password2 = "",
                config = config,
            )

        assertEquals("lomo/memo/note.md", encrypted)
        assertEquals(
            "lomo/memo/note.md",
            codec.decryptKey(
                encryptedKey = encrypted,
                password = "secret-pass",
                password2 = "",
                config = config,
            ),
        )
    }

    @Test
    fun `password2 changes filename derivation to match rclone`() {
        val encrypted =
            codec.encryptKey(
                key = "lomo/memo/note.md",
                password = "secret-pass",
                password2 = "secret-salt",
                config = standardConfig,
            )

        assertEquals("aN64pKS2-EEzrQ3FmeCN1w/8QRhr5B4sqeo7vJHhbAl9w/Y0fzQO6pGEg_6_jF1V4IzQ", encrypted)
    }

    @Test
    fun `password2 decrypts official rclone ciphertext`() {
        assertArrayEquals(
            "hello".toByteArray(),
            codec.decryptBytes(
                encrypted = password2HelloPayload,
                password = "secret-pass",
                password2 = "secret-salt",
            ),
        )
    }

    private companion object {
        val password2HelloPayload =
            byteArrayOf(
                0x52,
                0x43,
                0x4C,
                0x4F,
                0x4E,
                0x45,
                0x00,
                0x00,
                0xF7.toByte(),
                0xCE.toByte(),
                0xDD.toByte(),
                0x2D,
                0x35,
                0x59,
                0xAB.toByte(),
                0x47,
                0xA2.toByte(),
                0x57,
                0xE1.toByte(),
                0x04,
                0x4D,
                0xE2.toByte(),
                0x8D.toByte(),
                0x56,
                0x4C,
                0x59,
                0x48,
                0x55,
                0xE2.toByte(),
                0xBD.toByte(),
                0x17,
                0x09,
                0xBD.toByte(),
                0x5D,
                0xC5.toByte(),
                0x1B,
                0x76,
                0x86.toByte(),
                0x62,
                0xC3.toByte(),
                0x24,
                0x69,
                0x1B,
                0x92.toByte(),
                0x43,
                0x68,
                0xFD.toByte(),
                0xA1.toByte(),
                0xFE.toByte(),
                0xB6.toByte(),
                0x4B,
                0x0D,
                0x06,
            )
    }
}
