package com.lomo.domain.usecase

import com.lomo.domain.model.S3RcloneFilenameEncoding
import com.lomo.domain.model.S3RcloneFilenameEncryption
import com.lomo.domain.repository.S3SyncRepository
import com.lomo.domain.repository.SyncPolicyRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: S3SyncSettingsUseCase
 * - Behavior focus: S3 settings orchestration must expose and mutate the full rclone crypt configuration contract, including password2 and filename strategy fields.
 * - Observable outcomes: repository mutation invocations, credential status checks, and observed rclone configuration flows.
 * - Red phase: Fails before the fix because the use case only exposes the coarse encryption mode plus a single password field.
 * - Excludes: S3 transport behavior, codec internals, and UI rendering.
 */
class S3SyncSettingsUseCaseStrongCompatTest {
    private val s3SyncRepository: S3SyncRepository = mockk(relaxed = true)
    private val syncPolicyRepository: SyncPolicyRepository = mockk(relaxed = true)
    private val syncAndRebuildUseCase: SyncAndRebuildUseCase = mockk(relaxed = true)

    private val useCase =
        S3SyncSettingsUseCase(
            s3SyncRepository = s3SyncRepository,
            syncPolicyRepository = syncPolicyRepository,
            syncAndRebuildUseCase = syncAndRebuildUseCase,
        )

    @Test
    fun `advanced rclone settings delegate to repository`() =
        runTest {
            coEvery { s3SyncRepository.setEncryptionPassword2("secret-salt") } returns Unit
            coEvery { s3SyncRepository.setRcloneFilenameEncryption(S3RcloneFilenameEncryption.OBFUSCATE) } returns Unit
            coEvery { s3SyncRepository.setRcloneFilenameEncoding(S3RcloneFilenameEncoding.BASE32768) } returns Unit
            coEvery { s3SyncRepository.setRcloneDirectoryNameEncryption(false) } returns Unit
            coEvery { s3SyncRepository.setRcloneDataEncryptionEnabled(false) } returns Unit
            coEvery { s3SyncRepository.setRcloneEncryptedSuffix("none") } returns Unit

            useCase.updateEncryptionPassword2("secret-salt")
            useCase.updateRcloneFilenameEncryption(S3RcloneFilenameEncryption.OBFUSCATE)
            useCase.updateRcloneFilenameEncoding(S3RcloneFilenameEncoding.BASE32768)
            useCase.updateRcloneDirectoryNameEncryption(false)
            useCase.updateRcloneDataEncryptionEnabled(false)
            useCase.updateRcloneEncryptedSuffix("none")

            coVerify(exactly = 1) { s3SyncRepository.setEncryptionPassword2("secret-salt") }
            coVerify(exactly = 1) {
                s3SyncRepository.setRcloneFilenameEncryption(S3RcloneFilenameEncryption.OBFUSCATE)
            }
            coVerify(exactly = 1) {
                s3SyncRepository.setRcloneFilenameEncoding(S3RcloneFilenameEncoding.BASE32768)
            }
            coVerify(exactly = 1) { s3SyncRepository.setRcloneDirectoryNameEncryption(false) }
            coVerify(exactly = 1) { s3SyncRepository.setRcloneDataEncryptionEnabled(false) }
            coVerify(exactly = 1) { s3SyncRepository.setRcloneEncryptedSuffix("none") }
        }

    @Test
    fun `advanced credential status includes password2`() =
        runTest {
            coEvery { s3SyncRepository.isEncryptionPassword2Configured() } returns true

            assertTrue(useCase.isEncryptionPassword2Configured())
        }

    @Test
    fun `advanced rclone settings are observable`() =
        runTest {
            every { s3SyncRepository.getRcloneFilenameEncryption() } returns
                flowOf(S3RcloneFilenameEncryption.OBFUSCATE)
            every { s3SyncRepository.getRcloneFilenameEncoding() } returns
                flowOf(S3RcloneFilenameEncoding.BASE32768)
            every { s3SyncRepository.getRcloneDirectoryNameEncryption() } returns flowOf(false)
            every { s3SyncRepository.getRcloneDataEncryptionEnabled() } returns flowOf(false)
            every { s3SyncRepository.getRcloneEncryptedSuffix() } returns flowOf("none")

            assertEquals(
                S3RcloneFilenameEncryption.OBFUSCATE,
                useCase.observeRcloneFilenameEncryption().first(),
            )
            assertEquals(
                S3RcloneFilenameEncoding.BASE32768,
                useCase.observeRcloneFilenameEncoding().first(),
            )
            assertEquals(false, useCase.observeRcloneDirectoryNameEncryption().first())
            assertEquals(false, useCase.observeRcloneDataEncryptionEnabled().first())
            assertEquals("none", useCase.observeRcloneEncryptedSuffix().first())
        }
}
