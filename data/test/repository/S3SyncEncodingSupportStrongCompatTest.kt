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
import com.lomo.domain.model.S3RcloneCryptConfig
import com.lomo.domain.model.S3RcloneFilenameEncoding
import com.lomo.domain.model.S3RcloneFilenameEncryption
import java.nio.charset.StandardCharsets
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: S3SyncEncodingSupport
 * - Behavior focus: rclone strong-compat path and payload encoding across base32768, plaintext directories, suffix-based off mode, and no-data-encryption passthrough.
 * - Observable outcomes: exact remote keys under the configured prefix plus reversible content handling.
 * - TDD proof: Fails before the fix because encoding support only understands NONE, OpenSSL, and the old base64/base32 rclone subset.
 * - Excludes: AWS transport, sync planning, metadata persistence, and UI rendering.
 */
class S3SyncEncodingSupportStrongCompatTest : DataFunSpec() {
    init {
        test("remotePathFor uses base32768 filename encoding when configured") { `remotePathFor uses base32768 filename encoding when configured`() }

        test("remotePathFor leaves directory names plaintext when configured") { `remotePathFor leaves directory names plaintext when configured`() }

        test("remotePathFor appends suffix when filename encryption is off") { `remotePathFor appends suffix when filename encryption is off`() }

        test("encodeContent returns plaintext when rclone data encryption is disabled") { `encodeContent returns plaintext when rclone data encryption is disabled`() }
    }


    private val support = S3SyncEncodingSupport()

    private fun `remotePathFor uses base32768 filename encoding when configured`() {
        val config = config(filenameEncoding = S3RcloneFilenameEncoding.BASE32768)

        val remotePath = support.remotePathFor("lomo/memo/note.md", config)

        remotePath shouldBe "vault/堜鹛蔃㺓ꊈ袠ᦈ䃢租/ꁪ殁㲯㧢浀龹耵㭴鳟/ꈚ負畇秜豐渷葊粆敟"
        support.decodeRelativePath(remotePath, config) shouldBe "lomo/memo/note.md"
    }

    private fun `remotePathFor leaves directory names plaintext when configured`() {
        val config = config(directoryNameEncryption = false)

        val remotePath = support.remotePathFor("attachments/screenshots/shot.png", config)

        remotePath shouldBe "vault/attachments/screenshots/WYGqdZq0CGWonT5SwE77fw"
        support.decodeRelativePath(remotePath, config) shouldBe "attachments/screenshots/shot.png"
    }

    private fun `remotePathFor appends suffix when filename encryption is off`() {
        val config = config(filenameEncryption = S3RcloneFilenameEncryption.OFF)

        val remotePath = support.remotePathFor("lomo/memo/note.md", config)

        remotePath shouldBe "vault/lomo/memo/note.md.bin"
        support.decodeRelativePath(remotePath, config) shouldBe "lomo/memo/note.md"
    }

    private fun `encodeContent returns plaintext when rclone data encryption is disabled`() {
        val config = config(dataEncryptionEnabled = false)
        val plaintext = "hello rclone no-data".toByteArray(StandardCharsets.UTF_8)

        support.encodeContent(plaintext, config) shouldBe plaintext
        support.decodeContent(plaintext, config) shouldBe plaintext
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
