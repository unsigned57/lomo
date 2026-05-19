/*
 * Behavior Contract:
 * - Unit under test: SaveImageUseCaseTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenarios:
 * - Happy: standard happy path for SaveImageUseCaseTest.
 * - Boundary: boundary and edge cases for SaveImageUseCaseTest.
 * - Failure: failure and error scenarios for SaveImageUseCaseTest.
 * - Must-not-happen: invariants are never violated for SaveImageUseCaseTest.
 *
 * - Behavior focus: test behavioral outcomes of SaveImageUseCaseTest.
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


import com.lomo.domain.model.StorageLocation
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeMediaRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class SaveImageUseCaseTest : DomainFunSpec() {
    private val mediaRepository = FakeMediaRepository()
    private val useCase = SaveImageUseCase(mediaRepository)
    init {
        test("saveWithCacheSyncStatus returns success when both save and cache sync succeed") {
            runTest {
                        val source = StorageLocation("uri")
                        val saved = StorageLocation("/images/a.jpg")
                        mediaRepository.nextImportResult = saved

                        val result = useCase.saveWithCacheSyncStatus(source)

                        result shouldBe SaveImageResult.SavedAndCacheSynced(saved)
                        mediaRepository.importedSources shouldBe listOf(source)
                        mediaRepository.refreshImageLocationsCallCount shouldBe 0
                    }
        }

        test("saveWithCacheSyncStatus rethrows import failure and skips full refresh") {
            runTest {
                        val source = StorageLocation("uri")
                        val failure = IllegalArgumentException("invalid source")
                        mediaRepository.importFailure = failure

                        val thrown = runCatching { useCase.saveWithCacheSyncStatus(source) }.exceptionOrNull()

                        thrown shouldBe failure
                        mediaRepository.refreshImageLocationsCallCount shouldBe 0
                    }
        }
    }
}
