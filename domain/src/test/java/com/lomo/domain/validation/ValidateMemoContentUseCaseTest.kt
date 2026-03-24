package com.lomo.domain.usecase

import com.lomo.domain.model.MemoConstraints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: ValidateMemoContentUseCase
 * - Behavior focus: create vs update validation branches and exception compatibility.
 * - Observable outcomes: validation result type, error message payloads, and thrown exception types.
 * - Excludes: repository interaction, UI rendering, and unrelated memo mutation flows.
 */
class ValidateMemoContentUseCaseTest {
    private val validator = ValidateMemoContentUseCase()

    @Test
    fun `validateCreate returns empty-content invalid for blank input`() {
        val result = validator.validateCreate("   ")
        assertTrue(result is MemoValidationResult.Invalid.EmptyContentForCreate)
    }

    @Test
    fun `validateCreate returns content-too-long invalid with details`() {
        val content = "a".repeat(MemoConstraints.MAX_MEMO_LENGTH + 1)
        val result = validator.validateCreate(content)

        assertTrue(result is MemoValidationResult.Invalid.ContentTooLong)
        val invalid = result as MemoValidationResult.Invalid.ContentTooLong
        assertEquals(MemoConstraints.MAX_MEMO_LENGTH, invalid.maxLength)
        assertEquals(content.length, invalid.actualLength)
        assertEquals(ValidateMemoContentUseCase.lengthExceededMessage(), invalid.message)
    }

    @Test
    fun `requireValidForCreate throws domain validation exception`() {
        val exception =
            assertThrows(MemoValidationException::class.java) {
                validator.requireValidForCreate(" ")
            }

        assertEquals(ValidateMemoContentUseCase.EMPTY_CONTENT_MESSAGE, exception.message)
        assertTrue(exception.reason is MemoValidationResult.Invalid.EmptyContentForCreate)
    }

    @Test
    fun `validateUpdate returns empty-content invalid for blank input`() {
        val result = validator.validateUpdate(" ")
        assertTrue(result is MemoValidationResult.Invalid.EmptyContentForUpdate)
    }

    @Test
    fun `validateUpdate rejects content above max length`() {
        val content = "a".repeat(MemoConstraints.MAX_MEMO_LENGTH + 1)
        val result = validator.validateUpdate(content)

        assertTrue(result is MemoValidationResult.Invalid.ContentTooLong)
    }

    @Test
    fun `requireValidForUpdate remains exception-compatible`() {
        val content = "a".repeat(MemoConstraints.MAX_MEMO_LENGTH + 1)

        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                validator.requireValidForUpdate(content)
            }

        assertEquals(ValidateMemoContentUseCase.lengthExceededMessage(), exception.message)
    }
}
