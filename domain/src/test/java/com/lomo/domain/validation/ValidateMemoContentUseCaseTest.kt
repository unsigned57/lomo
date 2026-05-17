/*
 * Test Contract:
 * - Unit under test: ValidateMemoContentUseCaseTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for ValidateMemoContentUseCaseTest.
 * - Boundary: boundary and edge cases for ValidateMemoContentUseCaseTest.
 * - Failure: failure and error scenarios for ValidateMemoContentUseCaseTest.
 * - Must-not-happen: invariants are never violated for ValidateMemoContentUseCaseTest.
 *
 * - Behavior focus: test behavioral outcomes of ValidateMemoContentUseCaseTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.domain.usecase

import com.lomo.domain.model.MemoConstraints
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/*
 * Test Contract:
 * - Unit under test: ValidateMemoContentUseCase
 * - Behavior focus: create vs update validation branches and exception compatibility.
 * - Observable outcomes: validation result type, error message payloads, and thrown exception types.
 * - Excludes: repository interaction, UI rendering, and unrelated memo mutation flows.
 */
class ValidateMemoContentUseCaseTest : DomainFunSpec() {
    private val validator = ValidateMemoContentUseCase()

    init {
        test("validateCreate returns empty-content invalid for blank input") {
            val result = validator.validateCreate("   ")
            result.shouldBeInstanceOf<MemoValidationResult.Invalid.EmptyContentForCreate>()
        }

        test("validateCreate returns content-too-long invalid with details") {
            val content = "a".repeat(MemoConstraints.MAX_MEMO_LENGTH + 1)
            val result = validator.validateCreate(content)

            val invalid = result.shouldBeInstanceOf<MemoValidationResult.Invalid.ContentTooLong>()
            invalid.maxLength shouldBe MemoConstraints.MAX_MEMO_LENGTH
            invalid.actualLength shouldBe content.length
            invalid.message shouldBe ValidateMemoContentUseCase.lengthExceededMessage()
        }

        test("requireValidForCreate throws domain validation exception") {
            val exception =
                shouldThrow<MemoValidationException> {
                    validator.requireValidForCreate(" ")
                }

            exception.message shouldBe ValidateMemoContentUseCase.EMPTY_CONTENT_MESSAGE
            exception.reason.shouldBeInstanceOf<MemoValidationResult.Invalid.EmptyContentForCreate>()
        }

        test("validateUpdate returns empty-content invalid for blank input") {
            val result = validator.validateUpdate(" ")
            result.shouldBeInstanceOf<MemoValidationResult.Invalid.EmptyContentForUpdate>()
        }

        test("validateUpdate rejects content above max length") {
            val content = "a".repeat(MemoConstraints.MAX_MEMO_LENGTH + 1)
            val result = validator.validateUpdate(content)

            result.shouldBeInstanceOf<MemoValidationResult.Invalid.ContentTooLong>()
        }

        test("requireValidForUpdate remains exception-compatible") {
            val content = "a".repeat(MemoConstraints.MAX_MEMO_LENGTH + 1)

            val exception =
                shouldThrow<IllegalArgumentException> {
                    validator.requireValidForUpdate(content)
                }

            exception.message shouldBe ValidateMemoContentUseCase.lengthExceededMessage()
        }
    }
}
