package com.lomo.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: shouldUseCenteredBody, bodyTextSizeSp, and quote line layout policy.
 * - Behavior focus: centered-body layout policy, body text size thresholds, and share-card quote block visual affordance.
 * - Observable outcomes: Boolean decision to use centered body, selected body text size, and quote indicator/text layout metrics.
 * - Red phase: Quote layout test fails before the fix because share-card quote lines are rendered as plain text without a rounded indicator bar or text inset.
 * - Test Change Justification: reason category = pure refactor preserved behavior; removed the obsolete activeDayCountText constructor argument after recorded-days footer content was deleted. Coverage is preserved by keeping the same centered-body and text-size assertions. This is not fitting the test to the implementation because the assertions remain identical and only the input shape changed.
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

    @Test
    fun `quote layout reserves rounded indicator bar and text inset`() {
        val spec = shareCardLayoutSpecForTest(contentWidth = 320)

        val style = resolveShareCardQuoteLayoutStyle(spec)

        assertEquals(4f, style.indicatorWidth, 0.0f)
        assertEquals(2f, style.indicatorCornerRadius, 0.0f)
        assertEquals(12f, style.textStartOffset, 0.0f)
        assertEquals(308, style.textWidth)
    }
}

private fun shareCardLayoutSpecForTest(contentWidth: Int): ShareCardLayoutSpec =
    ShareCardLayoutSpec(
        canvasWidth = 400,
        outerPadding = 0f,
        cardPadding = 0f,
        cardCorner = 0f,
        lineSpacing = 0f,
        tagBottomSpacing = 0f,
        titleBottomSpacing = 0f,
        codeHorizontalPadding = 0f,
        codeVerticalPadding = 0f,
        codeCorner = 0f,
        imageCorner = 0f,
        imageVerticalPadding = 0f,
        maxImageHeightPx = 0f,
        dividerTopSpacing = 0f,
        dividerStrokeWidth = 0f,
        footerRowTopSpacing = 0f,
        minCardHeight = 0f,
        contentWidth = contentWidth,
        quoteIndicatorWidth = 4f,
        quoteIndicatorCornerRadius = 2f,
        quoteTextStartPadding = 8f,
    )
