package com.lomo.ui.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: MemoTextLayoutEngine in the ui-components text layer.
 * - Behavior focus: Compose-native memo text layout must support CJK justification while keeping
 *   English and English-dominant prose ragged-right, preserving configured base letter spacing,
 *   and respecting parent visible height limits for collapsed previews.
 * - Observable outcomes: line widths, chosen expansion slots, forced long-token breaks, and
 *   protected-range expansion exclusion, ellipsis placement, and resolved max visible lines.
 * - Red phase: Fails before the English-layout fix because the layout engine justifies pure
 *   English and English-dominant non-final lines by stretching spaces to the container width.
 * - Excludes: OEM glyph rasterization, Canvas drawing pixels, Android TextView internals, and
 *   Compose semantics tree inspection.
 */
class MemoTextLayoutEngineTest {
    private val measurer = FakeMemoTextMeasurer()
    private val engine = MemoTextLayoutEngine(measurer)

    @Test
    fun `pure cjk non-final line expands cjk character slots to container width`() {
        val layout =
            engine.layout(
                MemoTextLayoutInput(
                    text = "中文中文中文中文",
                    maxWidthPx = 44f,
                    baseLetterSpacingPx = 0f,
                ),
            )

        val firstLine = layout.lines.first()
        assertTrue(firstLine.isJustified)
        assertEquals(44f, firstLine.visualWidthPx, FLOAT_DELTA)
        assertTrue(firstLine.expansionSlots.all { it.kind == MemoTextExpansionSlotKind.CjkCharacter })
        assertTrue(firstLine.expansionSlots.all { it.extraWidthPx > 0f })
        assertFalse(layout.lines.last().isJustified)
    }

    @Test
    fun `cjk dominant mixed cjk latin justifies without expanding inside latin words`() {
        val layout =
            engine.layout(
                MemoTextLayoutInput(
                    text = "今天设计中文 review 中文排版内容",
                    maxWidthPx = 110f,
                    baseLetterSpacingPx = 0f,
                ),
            )

        val firstLine = layout.lines.first()
        assertTrue(firstLine.isJustified)
        assertEquals(110f, firstLine.visualWidthPx, FLOAT_DELTA)
        assertTrue(firstLine.runs.any { it.text == "review" })
        assertTrue(firstLine.expansionSlots.none { slot -> slot.leftText == "rev" || slot.rightText == "iew" })
        assertTrue(firstLine.expansionSlots.isNotEmpty())
    }

    @Test
    fun `pure english prose keeps ragged right spacing instead of justifying spaces`() {
        val layout =
            engine.layout(
                MemoTextLayoutInput(
                    text = "alpha beta gamma delta",
                    maxWidthPx = 60f,
                    baseLetterSpacingPx = 0f,
                ),
            )

        val firstLine = layout.lines.first()
        assertFalse(firstLine.isJustified)
        assertTrue(firstLine.expansionSlots.isEmpty())
        assertEquals(firstLine.naturalWidthPx, firstLine.visualWidthPx, FLOAT_DELTA)
    }

    @Test
    fun `english dominant mixed prose keeps ragged right spacing`() {
        val layout =
            engine.layout(
                MemoTextLayoutInput(
                    text = "today review memo 设计",
                    maxWidthPx = 80f,
                    baseLetterSpacingPx = 0f,
                ),
            )

        val firstLine = layout.lines.first()
        assertFalse(firstLine.isJustified)
        assertTrue(firstLine.expansionSlots.isEmpty())
        assertEquals(firstLine.naturalWidthPx, firstLine.visualWidthPx, FLOAT_DELTA)
    }

    @Test
    fun `configured letter spacing participates in natural width without disabling justification`() {
        val withoutSpacing =
            engine.layout(
                MemoTextLayoutInput(
                    text = "中文中文中文中文中文",
                    maxWidthPx = 44f,
                    baseLetterSpacingPx = 0f,
                ),
            )
        val withSpacing =
            engine.layout(
                MemoTextLayoutInput(
                    text = "中文中文中文中文中文",
                    maxWidthPx = 44f,
                    baseLetterSpacingPx = 1f,
                ),
            )

        assertTrue(withSpacing.lines.first().isJustified)
        assertEquals(44f, withSpacing.lines.first().visualWidthPx, FLOAT_DELTA)
        assertTrue(withSpacing.lines.first().naturalWidthPx > withoutSpacing.lines.first().naturalWidthPx)
    }

    @Test
    fun `punctuation and protected ranges are not used as expansion interiors`() {
        val layout =
            engine.layout(
                MemoTextLayoutInput(
                    text = "中文「链接文本」中文",
                    maxWidthPx = 74f,
                    baseLetterSpacingPx = 0f,
                    protectedRanges = listOf(MemoTextProtectedRange(start = 3, end = 7)),
                ),
            )

        val firstLine = layout.lines.first()
        assertTrue(firstLine.isJustified)
        assertTrue(firstLine.expansionSlots.none { it.leftText == "「" || it.rightText == "」" })
        assertTrue(firstLine.expansionSlots.none { it.boundaryOffset in 4..6 })
    }

    @Test
    fun `oversized latin token uses forced character breaks instead of overflowing indefinitely`() {
        val layout =
            engine.layout(
                MemoTextLayoutInput(
                    text = "supercalifragilistic",
                    maxWidthPx = 25f,
                    baseLetterSpacingPx = 0f,
                ),
            )

        assertTrue(layout.lines.size > 1)
        assertTrue(layout.lines.any { it.wasForcedBreak })
        assertTrue(layout.lines.all { it.visualWidthPx <= 25f + FLOAT_DELTA })
    }

    @Test
    fun `ellipsis replaces hidden content on final visible line`() {
        val layout =
            engine.layout(
                MemoTextLayoutInput(
                    text = "中文中文中文中文中文中文",
                    maxWidthPx = 44f,
                    maxLines = 1,
                    ellipsizeLastVisibleLine = true,
                ),
            )

        val line = layout.lines.single()
        assertFalse(line.isJustified)
        assertTrue(line.runs.joinToString(separator = "") { it.text }.length < layout.text.length)
        assertEquals(5f, line.ellipsis?.widthPx ?: 0f, FLOAT_DELTA)
        assertTrue(line.visualWidthPx <= 44f + FLOAT_DELTA)
    }

    @Test
    fun `finite parent height caps collapsed preview layout lines`() {
        val resolved =
            resolveMemoTextLayoutMaxLines(
                requestedMaxLines = Int.MAX_VALUE,
                maxHeightPx = 72,
                lineHeightPx = 24f,
            )

        assertEquals(3, resolved)
    }

    @Test
    fun `finite parent height never expands explicit max lines`() {
        val resolved =
            resolveMemoTextLayoutMaxLines(
                requestedMaxLines = 2,
                maxHeightPx = 240,
                lineHeightPx = 24f,
            )

        assertEquals(2, resolved)
    }

    @Test
    fun `unbounded parent height keeps requested max lines`() {
        val resolved =
            resolveMemoTextLayoutMaxLines(
                requestedMaxLines = Int.MAX_VALUE,
                maxHeightPx = Int.MAX_VALUE,
                lineHeightPx = 24f,
            )

        assertEquals(Int.MAX_VALUE, resolved)
    }

    @Test
    fun `tiny finite parent height keeps one reachable line`() {
        val resolved =
            resolveMemoTextLayoutMaxLines(
                requestedMaxLines = Int.MAX_VALUE,
                maxHeightPx = 1,
                lineHeightPx = 24f,
            )

        assertEquals(1, resolved)
    }

    private companion object {
        private const val FLOAT_DELTA = 0.0001f
    }
}

private class FakeMemoTextMeasurer : MemoTextMeasurer {
    override fun measureText(
        text: String,
        start: Int,
        end: Int,
    ): Float {
        var width = 0f
        var index = start
        while (index < end) {
            val codePoint = Character.codePointAt(text, index)
            width +=
                when {
                    Character.isWhitespace(codePoint) -> 4f
                    Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN -> 10f
                    codePoint.toChar().isChineseLayoutPunctuationForTest() -> 6f
                    else -> 5f
                }
            index += Character.charCount(codePoint)
        }
        return width
    }
}

private fun Char.isChineseLayoutPunctuationForTest(): Boolean =
    this == '「' || this == '」' || this == '，' || this == '。'
