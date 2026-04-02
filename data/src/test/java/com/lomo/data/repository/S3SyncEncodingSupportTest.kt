package com.lomo.data.repository

import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/*
 * Test Contract:
 * - Unit under test: S3SyncEncodingSupport
 * - Behavior focus: S3 remote path encoding uses the configured prefix plus vault-root-relative paths without forcing a legacy lomo/ root, while content encoding still round-trips under rclone crypt mode.
 * - Observable outcomes: prefixed or root-level remote keys, reversible path decoding, and reversible payload decoding.
 * - Red phase: Fails before the fix because the S3 key contract was still coupled to the legacy lomo/ path model instead of vault-root-relative paths.
 * - Excludes: AWS transport, sync planning, metadata persistence, and UI rendering.
 */
class S3SyncEncodingSupportTest {
    private val support = S3SyncEncodingSupport()

    private val rcloneConfig =
        S3ResolvedConfig(
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
        )

    @Test
    fun `remotePathFor and decodeRelativePath round-trip in rclone crypt mode`() {
        val relativePath = "Projects/2026_03_24.md"

        val remotePath = support.remotePathFor(relativePath, rcloneConfig)

        assertTrue(remotePath.startsWith("vault/"))
        assertEquals(relativePath, support.decodeRelativePath(remotePath, rcloneConfig))
    }

    @Test
    fun `remotePathFor preserves vault root relative path when prefix is empty`() {
        val config = rcloneConfig.copy(prefix = "")
        val relativePath = "attachments/screenshots/shot.png"

        val remotePath = support.remotePathFor(relativePath, config)

        assertEquals(relativePath, support.decodeRelativePath(remotePath, config))
        assertTrue(!remotePath.startsWith("/"))
    }

    @Test
    fun `encodeContent and decodeContent round-trip in rclone crypt mode`() {
        val plaintext = "hello rclone crypt".toByteArray(StandardCharsets.UTF_8)

        val encrypted = support.encodeContent(plaintext, rcloneConfig)

        assertArrayEquals(plaintext, support.decodeContent(encrypted, rcloneConfig))
    }
}
