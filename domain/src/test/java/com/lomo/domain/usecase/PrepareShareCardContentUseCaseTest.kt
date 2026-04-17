package com.lomo.domain.usecase

import com.lomo.domain.model.ShareCardTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: PrepareShareCardContentUseCase
 * - Behavior focus: share-card tag extraction preserves user-facing tags and removes inline tags from body text.
 * - Observable outcomes: extracted tag list and final body text content.
 * - Red phase: Fails before the fix when inline emoji tags are ignored and remain in the body text.
 * - Excludes: presentation-layer truncation, typography, and UI rendering details.
 */
class PrepareShareCardContentUseCaseTest {
    private val useCase = PrepareShareCardContentUseCase()

    @Test
    fun `invoke keeps long content without truncation`() {
        val longContent = "x".repeat(4500)

        val result =
            useCase(
                ShareCardTextInput(
                    content = longContent,
                    sourceTags = emptyList(),
                ),
            )

        assertEquals(longContent, result.bodyText)
        assertFalse(result.bodyText.endsWith("..."))
    }

    @Test
    fun `invoke keeps all source tags without display truncation`() {
        val tags =
            (1..8).map { index ->
                "#feature_${"x".repeat(20)}_$index"
            }

        val result =
            useCase(
                ShareCardTextInput(
                    content = "memo body",
                    sourceTags = tags,
                ),
            )

        assertEquals(tags.map { it.removePrefix("#") }, result.tags)
    }

    @Test
    fun `invoke keeps original spacing semantics in body text`() {
        val result =
            useCase(
                ShareCardTextInput(
                    content = "line1  line2\n\n\nline3 #topic",
                    sourceTags = emptyList(),
                ),
            )

        assertEquals("line1  line2\n\n\nline3", result.bodyText)
    }

    @Test
    fun `invoke extracts inline emoji tags and removes them from body text`() {
        val result =
            useCase(
                ShareCardTextInput(
                    content = "计划 #😀工作 和 #🎉",
                    sourceTags = emptyList(),
                ),
            )

        assertEquals(listOf("😀工作", "🎉"), result.tags)
        assertEquals("计划 和", result.bodyText)
    }
}
