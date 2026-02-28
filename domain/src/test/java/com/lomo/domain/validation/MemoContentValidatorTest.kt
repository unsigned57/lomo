package com.lomo.domain.validation

import com.lomo.domain.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class MemoContentValidatorTest {
    private val validator = MemoContentValidator()

    @Test
    fun `validateCreate returns empty-content invalid for blank input`() {
        val result = validator.validateCreate("   ")
        assertTrue(result is MemoValidationResult.Invalid.EmptyContentForCreate)
    }

    @Test
    fun `validateCreate returns content-too-long invalid with details`() {
        val content = "a".repeat(AppConfig.MAX_MEMO_LENGTH + 1)
        val result = validator.validateCreate(content)

        assertTrue(result is MemoValidationResult.Invalid.ContentTooLong)
        val invalid = result as MemoValidationResult.Invalid.ContentTooLong
        assertEquals(AppConfig.MAX_MEMO_LENGTH, invalid.maxLength)
        assertEquals(content.length, invalid.actualLength)
        assertEquals(MemoContentValidator.lengthExceededMessage(), invalid.message)
    }

    @Test
    fun `requireValidForCreate throws domain validation exception`() {
        val exception =
            assertThrows(MemoValidationException::class.java) {
                validator.requireValidForCreate(" ")
            }

        assertEquals(MemoContentValidator.EMPTY_CONTENT_MESSAGE, exception.message)
        assertTrue(exception.reason is MemoValidationResult.Invalid.EmptyContentForCreate)
    }

    @Test
    fun `validateUpdate allows blank content`() {
        assertTrue(validator.validateUpdate("") is MemoValidationResult.Valid)
    }

    @Test
    fun `validateUpdate rejects content above max length`() {
        val content = "a".repeat(AppConfig.MAX_MEMO_LENGTH + 1)
        val result = validator.validateUpdate(content)

        assertTrue(result is MemoValidationResult.Invalid.ContentTooLong)
    }

    @Test
    fun `legacy validateForUpdate remains exception-compatible`() {
        val content = "a".repeat(AppConfig.MAX_MEMO_LENGTH + 1)

        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                validator.validateForUpdate(content)
            }

        assertEquals(MemoContentValidator.lengthExceededMessage(), exception.message)
    }
}
