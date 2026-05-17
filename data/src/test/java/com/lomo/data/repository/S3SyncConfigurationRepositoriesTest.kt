package com.lomo.data.repository


import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3RcloneFilenameEncoding
import com.lomo.domain.model.S3RcloneFilenameEncryption
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: S3 sync preference mapping helpers in S3SyncConfigurationRepositories.kt
 * - Behavior focus: legacy OpenSSL values must fall back safely while new rclone filename strategy preferences map to explicit enums and suffix defaults.
 * - Observable outcomes: parsed enum values and normalized suffix strings.
 * - Red phase: Fails before the fix because OpenSSL is still treated as a supported mode and no rclone filename strategy mappers exist.
 * - Excludes: DataStore I/O, keystore credential persistence, and UI rendering.
 */
class S3SyncConfigurationRepositoriesTest : DataFunSpec() {
    init {
        test("legacy openssl preference falls back to none") { `legacy openssl preference falls back to none`() }

        test("rclone filename preference parsers map known values and normalize suffix") { `rclone filename preference parsers map known values and normalize suffix`() }
    }


    private fun `legacy openssl preference falls back to none`() {
        s3EncryptionModeFromPreference("openssl") shouldBe S3EncryptionMode.NONE
    }

    private fun `rclone filename preference parsers map known values and normalize suffix`() {
        s3RcloneFilenameEncryptionFromPreference("obfuscate") shouldBe S3RcloneFilenameEncryption.OBFUSCATE
        s3RcloneFilenameEncodingFromPreference("base32768") shouldBe S3RcloneFilenameEncoding.BASE32768
        s3RcloneEncryptedSuffixFromPreference("none") shouldBe ""
        s3RcloneEncryptedSuffixFromPreference("") shouldBe ".bin"
    }
}
