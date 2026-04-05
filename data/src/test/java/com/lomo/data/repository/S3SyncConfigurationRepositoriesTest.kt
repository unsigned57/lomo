package com.lomo.data.repository

import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3RcloneFilenameEncoding
import com.lomo.domain.model.S3RcloneFilenameEncryption
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: S3 sync preference mapping helpers in S3SyncConfigurationRepositories.kt
 * - Behavior focus: legacy OpenSSL values must fall back safely while new rclone filename strategy preferences map to explicit enums and suffix defaults.
 * - Observable outcomes: parsed enum values and normalized suffix strings.
 * - Red phase: Fails before the fix because OpenSSL is still treated as a supported mode and no rclone filename strategy mappers exist.
 * - Excludes: DataStore I/O, keystore credential persistence, and UI rendering.
 */
class S3SyncConfigurationRepositoriesTest {
    @Test
    fun `legacy openssl preference falls back to none`() {
        assertEquals(S3EncryptionMode.NONE, s3EncryptionModeFromPreference("openssl"))
    }

    @Test
    fun `rclone filename preference parsers map known values and normalize suffix`() {
        assertEquals(
            S3RcloneFilenameEncryption.OBFUSCATE,
            s3RcloneFilenameEncryptionFromPreference("obfuscate"),
        )
        assertEquals(
            S3RcloneFilenameEncoding.BASE32768,
            s3RcloneFilenameEncodingFromPreference("base32768"),
        )
        assertEquals("", s3RcloneEncryptedSuffixFromPreference("none"))
        assertEquals(".bin", s3RcloneEncryptedSuffixFromPreference(""))
    }
}
