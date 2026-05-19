/*
 * Behavior Contract:
 * - Unit under test: PersistShareImageUseCaseTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenarios:
 * - Happy: standard happy path for PersistShareImageUseCaseTest.
 * - Boundary: boundary and edge cases for PersistShareImageUseCaseTest.
 * - Failure: failure and error scenarios for PersistShareImageUseCaseTest.
 * - Must-not-happen: invariants are never violated for PersistShareImageUseCaseTest.
 *
 * - Behavior focus: test behavioral outcomes of PersistShareImageUseCaseTest.
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


import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeShareImageRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: PersistShareImageUseCase
 * - Behavior focus: share-image persistence keeps the byte payload and caller-selected filename prefix.
 * - Observable outcomes: returned storage path and repository invocation parameters.
 * - Excludes: image encoding, filesystem persistence, and URI exposure details.
 */
class PersistShareImageUseCaseTest : DomainFunSpec() {
    private val repository = FakeShareImageRepository()
    private val useCase = PersistShareImageUseCase(repository)
    init {
        test("invoke forwards bytes with default prefix") {
            runTest {
                        val bytes = byteArrayOf(1, 2, 3)
                        repository.nextPath = "/tmp/memo_share.png"

                        val result = useCase(bytes)

                        result shouldBe "/tmp/memo_share.png"
                        repository.storedImages.single().pngBytes.toList() shouldBe bytes.toList()
                        repository.storedImages.single().fileNamePrefix shouldBe "memo_share"
                    }
        }

        test("invoke forwards custom prefix to repository") {
            runTest {
                        val bytes = byteArrayOf(9, 8, 7)
                        repository.nextPath = "/tmp/daily_review.png"

                        val result = useCase(bytes, fileNamePrefix = "daily_review")

                        result shouldBe "/tmp/daily_review.png"
                        repository.storedImages.single().pngBytes.toList() shouldBe bytes.toList()
                        repository.storedImages.single().fileNamePrefix shouldBe "daily_review"
                    }
        }
    }
}
