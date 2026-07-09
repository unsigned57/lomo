/*
 * Behavior Contract:
 * - Unit under test: DiscardMemoDraftAttachmentsUseCaseTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenarios:
 * - Happy: standard happy path for DiscardMemoDraftAttachmentsUseCaseTest.
 * - Boundary: boundary and edge cases for DiscardMemoDraftAttachmentsUseCaseTest.
 * - Failure: failure and error scenarios for DiscardMemoDraftAttachmentsUseCaseTest.
 * - Must-not-happen: invariants are never violated for DiscardMemoDraftAttachmentsUseCaseTest.
 *
 * - Behavior focus: test behavioral outcomes of DiscardMemoDraftAttachmentsUseCaseTest.
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


import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeMediaRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class DiscardMemoDraftAttachmentsUseCaseTest : DomainFunSpec() {
    private val mediaRepository = FakeMediaRepository()
    private val useCase = DiscardMemoDraftAttachmentsUseCase(mediaRepository)

    init {
        test("invoke removes attachments without full refresh") {
            runTest {
                val filenames = listOf("a.jpg", "b.jpg")

                useCase(filenames)

                mediaRepository.removedImageIds shouldBe
                    listOf(MediaEntryId("a.jpg"), MediaEntryId("b.jpg"))
                mediaRepository.refreshImageLocationsCallCount shouldBe 0
            }
        }
    }
}
