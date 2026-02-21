package com.lomo.domain.validation

import com.lomo.domain.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MemoContentValidatorTest {
    private val validator = MemoContentValidator()

    @Test
    fun `validateForCreate rejects blank content`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                validator.validateForCreate("   ")
            }

        assertEquals(MemoContentValidator.EMPTY_CONTENT_MESSAGE, exception.message)
    }

    @Test
    fun `validateForCreate rejects content above max length`() {
        val content = "a".repeat(AppConfig.MAX_MEMO_LENGTH + 1)

        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                validator.validateForCreate(content)
            }

        assertEquals(MemoContentValidator.lengthExceededMessage(), exception.message)
    }

    @Test
    fun `validateForUpdate allows blank content`() {
        validator.validateForUpdate("")
    }

    @Test
    fun `validateForUpdate rejects content above max length`() {
        val content = "a".repeat(AppConfig.MAX_MEMO_LENGTH + 1)

        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                validator.validateForUpdate(content)
            }

        assertEquals(MemoContentValidator.lengthExceededMessage(), exception.message)
    }
}
