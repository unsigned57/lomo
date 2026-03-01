package com.lomo.domain.usecase

import com.lomo.domain.model.MediaCategory
import com.lomo.domain.model.StorageLocation
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
    fun `currentRootLocation returns repository value`() =
        runTest {
            val expected = StorageLocation("/workspace")
            coEvery { directorySettingsRepository.currentRootLocation() } returns expected

            val result = useCase.currentRootLocation()

            assertEquals(expected, result)
        }

    @Test
    fun `ensureDefaultMediaDirectories creates only image workspace when requested`() =
        runTest {
            coEvery { mediaRepository.ensureCategoryWorkspace(MediaCategory.IMAGE) } returns null

            useCase.ensureDefaultMediaDirectories(forImage = true, forVoice = false)

            coVerify(exactly = 1) { mediaRepository.ensureCategoryWorkspace(MediaCategory.IMAGE) }
            coVerify(exactly = 0) { mediaRepository.ensureCategoryWorkspace(MediaCategory.VOICE) }
        }

    @Test
    fun `ensureDefaultMediaDirectories creates only voice workspace when requested`() =
        runTest {
            coEvery { mediaRepository.ensureCategoryWorkspace(MediaCategory.VOICE) } returns null

            useCase.ensureDefaultMediaDirectories(forImage = false, forVoice = true)

            coVerify(exactly = 0) { mediaRepository.ensureCategoryWorkspace(MediaCategory.IMAGE) }
            coVerify(exactly = 1) { mediaRepository.ensureCategoryWorkspace(MediaCategory.VOICE) }
        }

    @Test
    fun `ensureDefaultMediaDirectories skips creation when both flags are false`() =
        runTest {
            useCase.ensureDefaultMediaDirectories(forImage = false, forVoice = false)

            coVerify(exactly = 0) { mediaRepository.ensureCategoryWorkspace(MediaCategory.IMAGE) }
            coVerify(exactly = 0) { mediaRepository.ensureCategoryWorkspace(MediaCategory.VOICE) }
        }
}
