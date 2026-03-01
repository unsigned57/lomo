package com.lomo.domain.usecase

import com.lomo.domain.model.ShareCardTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

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
}
