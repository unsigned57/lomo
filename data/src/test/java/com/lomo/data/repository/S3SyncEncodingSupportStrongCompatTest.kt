package com.lomo.data.repository

import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3RcloneCryptConfig
import com.lomo.domain.model.S3RcloneFilenameEncoding
import com.lomo.domain.model.S3RcloneFilenameEncryption
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets

/*
 * Test Contract:
 * - Unit under test: S3SyncEncodingSupport
 * - Behavior focus: rclone strong-compat path and payload encoding across base32768, plaintext directories, suffix-based off mode, and no-data-encryption passthrough.
 * - Observable outcomes: exact remote keys under the configured prefix plus reversible content handling.
 * - Red phase: Fails before the fix because encoding support only understands NONE, OpenSSL, and the old base64/base32 rclone subset.
 * - Excludes: AWS transport, sync planning, metadata persistence, and UI rendering.
 */
class S3SyncEncodingSupportStrongCompatTest {
    private val support = S3SyncEncodingSupport()

    @Test
    fun `remotePathFor uses base32768 filename encoding when configured`() {
        val config = config(filenameEncoding = S3RcloneFilenameEncoding.BASE32768)

        val remotePath = support.remotePathFor("lomo/memo/note.md", config)

        assertEquals("vault/堜鹛蔃㺓ꊈ袠ᦈ䃢租/ꁪ殁㲯㧢浀龹耵㭴鳟/ꈚ負畇秜豐渷葊粆敟", remotePath)
        assertEquals("lomo/memo/note.md", support.decodeRelativePath(remotePath, config))
    }

    @Test
    fun `remotePathFor leaves directory names plaintext when configured`() {
        val config = config(directoryNameEncryption = false)

        val remotePath = support.remotePathFor("attachments/screenshots/shot.png", config)

        assertEquals("vault/attachments/screenshots/WYGqdZq0CGWonT5SwE77fw", remotePath)
        assertEquals("attachments/screenshots/shot.png", support.decodeRelativePath(remotePath, config))
    }

    @Test
    fun `remotePathFor appends suffix when filename encryption is off`() {
        val config = config(filenameEncryption = S3RcloneFilenameEncryption.OFF)

        val remotePath = support.remotePathFor("lomo/memo/note.md", config)

        assertEquals("vault/lomo/memo/note.md.bin", remotePath)
        assertEquals("lomo/memo/note.md", support.decodeRelativePath(remotePath, config))
    }

    @Test
    fun `encodeContent returns plaintext when rclone data encryption is disabled`() {
        val config = config(dataEncryptionEnabled = false)
        val plaintext = "hello rclone no-data".toByteArray(StandardCharsets.UTF_8)

        assertArrayEquals(plaintext, support.encodeContent(plaintext, config))
        assertArrayEquals(plaintext, support.decodeContent(plaintext, config))
    }

    private fun config(
        filenameEncryption: S3RcloneFilenameEncryption = S3RcloneFilenameEncryption.STANDARD,
        directoryNameEncryption: Boolean = true,
        filenameEncoding: S3RcloneFilenameEncoding = S3RcloneFilenameEncoding.BASE64,
        dataEncryptionEnabled: Boolean = true,
    ) = S3ResolvedConfig(
        endpointUrl = "https://s3.example.com",
        region = "us-east-1",
        bucket = "bucket",
        prefix = "vault",
        accessKeyId = "access",
        secretAccessKey = "secret",
        sessionToken = null,
        pathStyle = S3PathStyle.AUTO,
        encryptionMode = S3EncryptionMode.RCLONE_CRYPT,
        encryptionPassword = "secret-pass",
        encryptionPassword2 = null,
        rcloneCryptConfig =
            S3RcloneCryptConfig(
                filenameEncryption = filenameEncryption,
                directoryNameEncryption = directoryNameEncryption,
                filenameEncoding = filenameEncoding,
                dataEncryptionEnabled = dataEncryptionEnabled,
                encryptedSuffix = ".bin",
            ),
    )
}
