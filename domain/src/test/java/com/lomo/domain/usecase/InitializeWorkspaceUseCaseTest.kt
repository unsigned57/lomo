/*
 * Behavior Contract:
 * - Unit under test: InitializeWorkspaceUseCaseTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenarios:
 * - Happy: standard happy path for InitializeWorkspaceUseCaseTest.
 * - Boundary: boundary and edge cases for InitializeWorkspaceUseCaseTest.
 * - Failure: failure and error scenarios for InitializeWorkspaceUseCaseTest.
 * - Must-not-happen: invariants are never violated for InitializeWorkspaceUseCaseTest.
 *
 * - Behavior focus: test behavioral outcomes of InitializeWorkspaceUseCaseTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - TDD proof: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.domain.usecase

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


import com.lomo.domain.model.MediaCategory
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeDirectorySettingsRepository
import com.lomo.domain.testing.fakes.FakeMediaRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class InitializeWorkspaceUseCaseTest : DomainFunSpec() {
    private val directorySettingsRepository = FakeDirectorySettingsRepository()
    private val mediaRepository = FakeMediaRepository()
    private val useCase = InitializeWorkspaceUseCase(directorySettingsRepository, mediaRepository)
    init {
        test("currentRootLocation returns repository value") {
            runTest {
                        val expected = StorageLocation("/workspace")
                        directorySettingsRepository.setLocation(StorageArea.ROOT, expected)

                        val result = useCase.currentRootLocation()

                        result shouldBe expected
                    }
        }

        test("ensureDefaultMediaDirectories creates only image workspace when requested") {
            runTest {
                        useCase.ensureDefaultMediaDirectories(forImage = true, forVoice = false)

                        mediaRepository.ensuredCategories shouldBe listOf(MediaCategory.IMAGE)
                    }
        }

        test("ensureDefaultMediaDirectories creates only voice workspace when requested") {
            runTest {
                        useCase.ensureDefaultMediaDirectories(forImage = false, forVoice = true)

                        mediaRepository.ensuredCategories shouldBe listOf(MediaCategory.VOICE)
                    }
        }

        test("ensureDefaultMediaDirectories skips creation when both flags are false") {
            runTest {
                        useCase.ensureDefaultMediaDirectories(forImage = false, forVoice = false)

                        mediaRepository.ensuredCategories shouldBe emptyList()
                    }
        }
    }
}
