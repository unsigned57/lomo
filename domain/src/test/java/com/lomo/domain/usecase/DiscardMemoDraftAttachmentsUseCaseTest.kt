/*
 * Test Contract:
 * - Unit under test: DiscardMemoDraftAttachmentsUseCaseTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for DiscardMemoDraftAttachmentsUseCaseTest.
 * - Boundary: boundary and edge cases for DiscardMemoDraftAttachmentsUseCaseTest.
 * - Failure: failure and error scenarios for DiscardMemoDraftAttachmentsUseCaseTest.
 * - Must-not-happen: invariants are never violated for DiscardMemoDraftAttachmentsUseCaseTest.
 *
 * - Behavior focus: test behavioral outcomes of DiscardMemoDraftAttachmentsUseCaseTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.domain.usecase

import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.testing.DomainFunSpec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

class DiscardMemoDraftAttachmentsUseCaseTest : DomainFunSpec() {
    private val mediaRepository: MediaRepository = mockk(relaxed = true)
    private val useCase = DiscardMemoDraftAttachmentsUseCase(mediaRepository)
    init {
        test("invoke removes attachments without full refresh") {
            runTest {
                        val filenames = listOf("a.jpg", "b.jpg")
                        coEvery { mediaRepository.removeImage(any()) } returns Unit

                        useCase(filenames)

                        coVerify { mediaRepository.removeImage(MediaEntryId("a.jpg")) }
                        coVerify { mediaRepository.removeImage(MediaEntryId("b.jpg")) }
                        coVerify(exactly = 0) { mediaRepository.refreshImageLocations() }
                    }
        }
    }
}
