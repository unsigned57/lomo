/*
 * Test Contract:
 * - Unit under test: SaveImageUseCaseTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for SaveImageUseCaseTest.
 * - Boundary: boundary and edge cases for SaveImageUseCaseTest.
 * - Failure: failure and error scenarios for SaveImageUseCaseTest.
 * - Must-not-happen: invariants are never violated for SaveImageUseCaseTest.
 *
 * - Behavior focus: test behavioral outcomes of SaveImageUseCaseTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.domain.usecase

import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

class SaveImageUseCaseTest : DomainFunSpec() {
    private val mediaRepository: MediaRepository = mockk()
    private val useCase = SaveImageUseCase(mediaRepository)
    init {
        test("saveWithCacheSyncStatus returns success when both save and cache sync succeed") {
            runTest {
                        val source = StorageLocation("uri")
                        val saved = StorageLocation("/images/a.jpg")
                        coEvery { mediaRepository.importImage(source) } returns saved

                        val result = useCase.saveWithCacheSyncStatus(source)

                        result shouldBe SaveImageResult.SavedAndCacheSynced(saved)
                        coVerify(exactly = 0) { mediaRepository.refreshImageLocations() }
                    }
        }
    }
    init {
        test("saveWithCacheSyncStatus rethrows import failure and skips full refresh") {
            runTest {
                        val source = StorageLocation("uri")
                        val failure = IllegalArgumentException("invalid source")
                        coEvery { mediaRepository.importImage(source) } throws failure

                        val thrown = runCatching { useCase.saveWithCacheSyncStatus(source) }.exceptionOrNull()

                        thrown shouldBe failure
                        coVerify(exactly = 0) { mediaRepository.refreshImageLocations() }
                    }
        }
    }
}
