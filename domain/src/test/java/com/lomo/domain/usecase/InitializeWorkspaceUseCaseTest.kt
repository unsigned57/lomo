package com.lomo.domain.usecase

import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.MediaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class InitializeWorkspaceUseCaseTest {
    private val directorySettingsRepository: DirectorySettingsRepository = mockk()
    private val mediaRepository: MediaRepository = mockk()
    private val useCase = InitializeWorkspaceUseCase(directorySettingsRepository, mediaRepository)

    @Test
    fun `currentRootDirectory returns repository value`() =
        runTest {
            coEvery { directorySettingsRepository.getRootDirectoryOnce() } returns "/workspace"

            val result = useCase.currentRootDirectory()

            assertEquals("/workspace", result)
        }

    @Test
    fun `ensureDefaultMediaDirectories creates only image directory when requested`() =
        runTest {
            coEvery { mediaRepository.createDefaultImageDirectory() } returns null

            useCase.ensureDefaultMediaDirectories(forImage = true, forVoice = false)

            coVerify(exactly = 1) { mediaRepository.createDefaultImageDirectory() }
            coVerify(exactly = 0) { mediaRepository.createDefaultVoiceDirectory() }
        }

    @Test
    fun `ensureDefaultMediaDirectories creates only voice directory when requested`() =
        runTest {
            coEvery { mediaRepository.createDefaultVoiceDirectory() } returns null

            useCase.ensureDefaultMediaDirectories(forImage = false, forVoice = true)

            coVerify(exactly = 0) { mediaRepository.createDefaultImageDirectory() }
            coVerify(exactly = 1) { mediaRepository.createDefaultVoiceDirectory() }
        }

    @Test
    fun `ensureDefaultMediaDirectories skips creation when both flags are false`() =
        runTest {
            useCase.ensureDefaultMediaDirectories(forImage = false, forVoice = false)

            coVerify(exactly = 0) { mediaRepository.createDefaultImageDirectory() }
            coVerify(exactly = 0) { mediaRepository.createDefaultVoiceDirectory() }
        }
}
