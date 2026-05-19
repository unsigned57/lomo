/*
 * Behavior Contract:
 * - Unit under test: ExtractShareAttachmentsUseCaseTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenarios:
 * - Happy: standard happy path for ExtractShareAttachmentsUseCaseTest.
 * - Boundary: boundary and edge cases for ExtractShareAttachmentsUseCaseTest.
 * - Failure: failure and error scenarios for ExtractShareAttachmentsUseCaseTest.
 * - Must-not-happen: invariants are never violated for ExtractShareAttachmentsUseCaseTest.
 *
 * - Behavior focus: test behavioral outcomes of ExtractShareAttachmentsUseCaseTest.
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
import io.kotest.matchers.shouldBe

class ExtractShareAttachmentsUseCaseTest : DomainFunSpec() {
    private val useCase = ExtractShareAttachmentsUseCase()
    init {
        test("extracts markdown wiki and audio local attachments while filtering remote") {
            val content =
                """
                ![img](images/a.png)
                ![[vault/b.jpg|cover]]
                [voice](voice/recording.m4a)
                ![remote](https://cdn.example.com/c.png)
                [remote-audio](http://example.com/d.mp3)
                """.trimIndent()

            val result = useCase(content)

            result.localAttachmentPaths shouldBe listOf("images/a.png", "vault/b.jpg", "voice/recording.m4a")
            result.attachmentUris shouldBe mapOf(
                    "images/a.png" to "images/a.png",
                    "vault/b.jpg" to "vault/b.jpg",
                    "voice/recording.m4a" to "voice/recording.m4a",
                )
        }

        test("extracts distinct local paths only") {
            val content =
                """
                ![img]( ./same.png )
                ![img2](./same.png)
                """.trimIndent()

            val result = useCase(content)

            result.localAttachmentPaths shouldBe listOf("./same.png")
        }
    }
}
