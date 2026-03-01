package com.lomo.domain.usecase

import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.WorkspaceTransitionRepository
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SwitchRootStorageUseCaseTest {
    @MockK(relaxed = true)
    private lateinit var directorySettingsRepository: DirectorySettingsRepository

    @MockK(relaxed = true)
    private lateinit var cleanupRepository: WorkspaceTransitionRepository

    private lateinit var useCase: SwitchRootStorageUseCase

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        useCase = SwitchRootStorageUseCase(directorySettingsRepository, cleanupRepository)
    }

    @Test
    fun `updateRootLocation triggers cleanup after successful switch`() =
        runTest {
            val location = StorageLocation("/tmp/lomo")
            coEvery { directorySettingsRepository.applyRootLocation(location) } just runs
            coEvery { cleanupRepository.clearMemoStateAfterWorkspaceTransition() } just runs

            useCase.updateRootLocation(location)

            coVerifyOrder {
                directorySettingsRepository.applyRootLocation(location)
                cleanupRepository.clearMemoStateAfterWorkspaceTransition()
            }
        }

    @Test
    fun `updateRootLocation does not cleanup when switch fails`() =
        runTest {
            val location = StorageLocation("content://root")
            coEvery { directorySettingsRepository.applyRootLocation(location) } throws IllegalStateException("failed")

            val error = runCatching { useCase.updateRootLocation(location) }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
            coVerify(exactly = 1) { directorySettingsRepository.applyRootLocation(location) }
            coVerify(exactly = 0) { cleanupRepository.clearMemoStateAfterWorkspaceTransition() }
        }
}
