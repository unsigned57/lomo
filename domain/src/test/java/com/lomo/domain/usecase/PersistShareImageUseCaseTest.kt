/*
 * Test Contract:
 * - Unit under test: PersistShareImageUseCaseTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for PersistShareImageUseCaseTest.
 * - Boundary: boundary and edge cases for PersistShareImageUseCaseTest.
 * - Failure: failure and error scenarios for PersistShareImageUseCaseTest.
 * - Must-not-happen: invariants are never violated for PersistShareImageUseCaseTest.
 *
 * - Behavior focus: test behavioral outcomes of PersistShareImageUseCaseTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.domain.usecase

import com.lomo.domain.repository.ShareImageRepository
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: PersistShareImageUseCase
 * - Behavior focus: share-image persistence keeps the byte payload and caller-selected filename prefix.
 * - Observable outcomes: returned storage path and repository invocation parameters.
 * - Excludes: image encoding, filesystem persistence, and URI exposure details.
 */
class PersistShareImageUseCaseTest : DomainFunSpec() {
    private val repository: ShareImageRepository = mockk()
    private val useCase = PersistShareImageUseCase(repository)
    init {
        test("invoke forwards bytes with default prefix") {
            runTest {
                        val bytes = byteArrayOf(1, 2, 3)
                        coEvery {
                            repository.storeShareImage(bytes, "memo_share")
                        } returns "/tmp/memo_share.png"

                        val result = useCase(bytes)

                        result shouldBe "/tmp/memo_share.png"
                        coVerify(exactly = 1) { repository.storeShareImage(bytes, "memo_share") }
                    }
        }
    }
    init {
        test("invoke forwards custom prefix to repository") {
            runTest {
                        val bytes = byteArrayOf(9, 8, 7)
                        coEvery {
                            repository.storeShareImage(bytes, "daily_review")
                        } returns "/tmp/daily_review.png"

                        val result = useCase(bytes, fileNamePrefix = "daily_review")

                        result shouldBe "/tmp/daily_review.png"
                        coVerify(exactly = 1) { repository.storeShareImage(bytes, "daily_review") }
                    }
        }
    }
}
