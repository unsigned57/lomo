package com.lomo.app.presentation.sharecard

import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: ShareCardDisplayFormatter
 * - Behavior focus: share-card tag normalization and plain-text body formatting contracts.
 * - Observable outcomes: rendered tag list, placeholder substitution, markdown-to-plain-text output.
 * - Excludes: bitmap rendering, typography/layout, and Android UI integration.
 */
class ShareCardDisplayFormatterTest {
    private val formatter = ShareCardDisplayFormatter()

    @Test
    fun `formatTagsForDisplay trims hashes deduplicates truncates and caps count`() {
        val tags =
            listOf(
                "  #Android  ",
                "#Android",
                "",
                "   ",
                "#Compose",
                "VeryLongTagNameThatShouldBeTrimmed",
                "#Sync",
                "#Git",
                "#Offline",
                "#Extra",
                "#Ignored",
            )

        val result = formatter.formatTagsForDisplay(tags)

        assertEquals(
            listOf(
                "Android",
                "Compose",
                "VeryLongTagNameTha",
                "Sync",
                "Git",
                "Offline",
            ),
            result,
        )
    }

    @Test
    fun `formatBodyText converts markdown constructs to stable share-card text`() {
        val result =
            formatter.formatBodyText(
                bodyText =
                    """
                    # Title
                    ### Subtitle
                    - bullet item
                    1. ordered item
                    > quoted
                    `inline`
                    ~~removed~~
                    ![voice](voice_01.m4a)
                    [Image]
                    [Image: cover.png]
                    """.trimIndent(),
                audioPlaceholder = "[Audio]",
                imagePlaceholder = "[Photo]",
                imageNamedPlaceholderPattern = "[Photo: %s]",
            )

        assertEquals(
            """
            ✦ Title
            • Subtitle
            • bullet item
            • ordered item
            │ quoted
            「inline」
            removed
            [Audio]
            [Photo]
            [Photo: cover.png]
            """.trimIndent(),
            result,
        )
    }

    @Test
    fun `formatBodyText indents code blocks and falls back when image pattern is invalid`() {
        val result =
            formatter.formatBodyText(
                bodyText =
                    """
                    ```kotlin
                    val x = 1
                    ```

                    [Image: screenshot.png]
                    """.trimIndent(),
                audioPlaceholder = "[Audio]",
                imagePlaceholder = "[Photo]",
                imageNamedPlaceholderPattern = "%q",
            )

        assertEquals(
            """
            val x = 1

            %q screenshot.png
            """.trimIndent(),
            result,
        )
    }
}
