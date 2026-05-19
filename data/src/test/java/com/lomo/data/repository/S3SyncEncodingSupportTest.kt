package com.lomo.data.repository

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



import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import java.nio.charset.StandardCharsets
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue

/*
 * Behavior Contract:
 * - Unit under test: S3SyncEncodingSupport
 * - Behavior focus: S3 remote path encoding uses the configured prefix plus vault-root-relative paths without forcing a legacy lomo/ root, while content encoding still round-trips under rclone crypt mode.
 * - Observable outcomes: prefixed or root-level remote keys, reversible path decoding, and reversible payload decoding.
 * - TDD proof: Fails before the fix because the S3 key contract was still coupled to the legacy lomo/ path model instead of vault-root-relative paths.
 * - Excludes: AWS transport, sync planning, metadata persistence, and UI rendering.
 */
class S3SyncEncodingSupportTest : DataFunSpec() {
    init {
        test("remotePathFor and decodeRelativePath round-trip in rclone crypt mode") { `remotePathFor and decodeRelativePath round-trip in rclone crypt mode`() }

        test("remotePathFor preserves vault root relative path when prefix is empty") { `remotePathFor preserves vault root relative path when prefix is empty`() }

        test("encodeContent and decodeContent round-trip in rclone crypt mode") { `encodeContent and decodeContent round-trip in rclone crypt mode`() }
    }


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

    private fun `remotePathFor and decodeRelativePath round-trip in rclone crypt mode`() {
        val relativePath = "Projects/2026_03_24.md"

        val remotePath = support.remotePathFor(relativePath, rcloneConfig)

        (remotePath.startsWith("vault/")).shouldBeTrue()
        support.decodeRelativePath(remotePath, rcloneConfig) shouldBe relativePath
    }

    private fun `remotePathFor preserves vault root relative path when prefix is empty`() {
        val config = rcloneConfig.copy(prefix = "")
        val relativePath = "attachments/screenshots/shot.png"

        val remotePath = support.remotePathFor(relativePath, config)

        support.decodeRelativePath(remotePath, config) shouldBe relativePath
        (!remotePath.startsWith("/")).shouldBeTrue()
    }

    private fun `encodeContent and decodeContent round-trip in rclone crypt mode`() {
        val plaintext = "hello rclone crypt".toByteArray(StandardCharsets.UTF_8)

        val encrypted = support.encodeContent(plaintext, rcloneConfig)

        support.decodeContent(encrypted, rcloneConfig) shouldBe plaintext
    }
}
