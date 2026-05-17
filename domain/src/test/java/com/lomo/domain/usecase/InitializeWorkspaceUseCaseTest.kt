/*
 * Test Contract:
 * - Unit under test: InitializeWorkspaceUseCaseTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for InitializeWorkspaceUseCaseTest.
 * - Boundary: boundary and edge cases for InitializeWorkspaceUseCaseTest.
 * - Failure: failure and error scenarios for InitializeWorkspaceUseCaseTest.
 * - Must-not-happen: invariants are never violated for InitializeWorkspaceUseCaseTest.
 *
 * - Behavior focus: test behavioral outcomes of InitializeWorkspaceUseCaseTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.domain.usecase

import com.lomo.domain.model.MediaCategory
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

class InitializeWorkspaceUseCaseTest : DomainFunSpec() {
    private val directorySettingsRepository: DirectorySettingsRepository = mockk()
    private val mediaRepository: MediaRepository = mockk()
    private val useCase = InitializeWorkspaceUseCase(directorySettingsRepository, mediaRepository)
    init {
        test("currentRootLocation returns repository value") {
            runTest {
                        val expected = StorageLocation("/workspace")
                        coEvery { directorySettingsRepository.currentRootLocation() } returns expected

                        val result = useCase.currentRootLocation()

                        result shouldBe expected
                    }
        }
    }
    init {
        test("ensureDefaultMediaDirectories creates only image workspace when requested") {
            runTest {
                        coEvery { mediaRepository.ensureCategoryWorkspace(MediaCategory.IMAGE) } returns null

                        useCase.ensureDefaultMediaDirectories(forImage = true, forVoice = false)

                        coVerify(exactly = 1) { mediaRepository.ensureCategoryWorkspace(MediaCategory.IMAGE) }
                        coVerify(exactly = 0) { mediaRepository.ensureCategoryWorkspace(MediaCategory.VOICE) }
                    }
        }
    }
    init {
        test("ensureDefaultMediaDirectories creates only voice workspace when requested") {
            runTest {
                        coEvery { mediaRepository.ensureCategoryWorkspace(MediaCategory.VOICE) } returns null

                        useCase.ensureDefaultMediaDirectories(forImage = false, forVoice = true)

                        coVerify(exactly = 0) { mediaRepository.ensureCategoryWorkspace(MediaCategory.IMAGE) }
                        coVerify(exactly = 1) { mediaRepository.ensureCategoryWorkspace(MediaCategory.VOICE) }
                    }
        }
    }
    init {
        test("ensureDefaultMediaDirectories skips creation when both flags are false") {
            runTest {
                        useCase.ensureDefaultMediaDirectories(forImage = false, forVoice = false)

                        coVerify(exactly = 0) { mediaRepository.ensureCategoryWorkspace(MediaCategory.IMAGE) }
                        coVerify(exactly = 0) { mediaRepository.ensureCategoryWorkspace(MediaCategory.VOICE) }
                    }
        }
    }
}
