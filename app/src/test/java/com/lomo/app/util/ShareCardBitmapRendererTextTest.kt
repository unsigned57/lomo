package com.lomo.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: shouldUseCenteredBody and bodyTextSizeSp
 * - Behavior focus: centered-body layout policy and body text size thresholds for share-card rendering.
 * - Observable outcomes: Boolean decision to use centered body for a given ShareCardRenderInput and ShareBodyLine list, plus the selected body text size.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: bitmap drawing, text paint construction, and Android typography/resource lookups.
 */
class ShareCardBitmapRendererTextTest {
    @Test
    fun `shouldUseCenteredBody returns true for short headerless paragraph only body`() {
        val input =
            ShareCardRenderInput(
                displayTags = emptyList(),
                title = null,
                safeText = "Short reflective note",
                imagePlaceholder = "[Image]",
                createdAtText = "2026-03-25 12:00",
                activeDayCountText = "",
                textLengthWithoutMarkers = 21,
                hasImages = false,
            )
        val bodyLines =
            listOf(
                ShareBodyLine("Short reflective note", ShareBodyLineType.Paragraph),
                ShareBodyLine(BLANK_LAYOUT_TEXT, ShareBodyLineType.Blank),
                ShareBodyLine("Second line", ShareBodyLineType.Paragraph),
            )

        assertTrue(shouldUseCenteredBody(input, bodyLines))
    }

    @Test
    fun `shouldUseCenteredBody rejects headers images long bodies and non paragraph lines`() {
        val baseInput =
            ShareCardRenderInput(
                displayTags = emptyList(),
                title = null,
                safeText = "Centered candidate",
                imagePlaceholder = "[Image]",
                createdAtText = "2026-03-25 12:00",
                activeDayCountText = "",
                textLengthWithoutMarkers = 18,
                hasImages = false,
            )
        val paragraphLines = listOf(ShareBodyLine("Centered candidate", ShareBodyLineType.Paragraph))

        assertFalse(
            shouldUseCenteredBody(
                baseInput.copy(displayTags = listOf("tag")),
                paragraphLines,
            ),
        )
        assertFalse(
            shouldUseCenteredBody(
                baseInput.copy(title = "Title"),
                paragraphLines,
            ),
        )
        assertFalse(
            shouldUseCenteredBody(
                baseInput.copy(hasImages = true),
                paragraphLines,
            ),
        )
        assertFalse(
            shouldUseCenteredBody(
                baseInput.copy(textLengthWithoutMarkers = SHORT_BODY_CENTER_THRESHOLD + 1),
                paragraphLines,
            ),
        )
        assertFalse(
            shouldUseCenteredBody(
                baseInput,
                listOf(ShareBodyLine("• bullet", ShareBodyLineType.Bullet)),
            ),
        )
        assertFalse(
            shouldUseCenteredBody(
                baseInput,
                List(SHORT_BODY_MAX_NON_BLANK_LINES + 1) { index ->
                    ShareBodyLine("line $index", ShareBodyLineType.Paragraph)
                },
            ),
        )
    }

    @Test
    fun `bodyTextSizeSp scales down only at configured thresholds`() {
        assertEquals(BODY_TEXT_SIZE_SHORT_SP, bodyTextSizeSp(BODY_TEXT_SIZE_SHORT_THRESHOLD), 0.0f)
        assertEquals(
            BODY_TEXT_SIZE_MEDIUM_SP,
            bodyTextSizeSp(BODY_TEXT_SIZE_SHORT_THRESHOLD + 1),
            0.0f,
        )
        assertEquals(BODY_TEXT_SIZE_LONG_SP, bodyTextSizeSp(BODY_TEXT_SIZE_MEDIUM_THRESHOLD + 1), 0.0f)
        assertEquals(BODY_TEXT_SIZE_DEFAULT_SP, bodyTextSizeSp(BODY_TEXT_SIZE_LONG_THRESHOLD + 1), 0.0f)
    }
}
