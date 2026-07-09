package com.lomo.ui.text

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: MemoTextLayoutEngine in the ui-components text layer.
 * - Behavior focus: Compose-native memo text layout must support CJK justification while keeping
 *   English and English-dominant prose ragged-right, preserving configured base letter spacing,
 *   and respecting parent visible height limits for collapsed previews.
 * - Observable outcomes: line widths, chosen expansion slots, forced long-token breaks, and
 *   protected-range expansion exclusion, ellipsis placement, and resolved max visible lines.
 * - TDD proof: Fails before the English-layout fix because the layout engine justifies pure
 *   English and English-dominant non-final lines by stretching spaces to the container width.
 * - Excludes: OEM glyph rasterization, Canvas drawing pixels, Android TextView internals, and
 *   Compose semantics tree inspection.
 */
class MemoTextLayoutEngineTest : UiComponentsFunSpec() {
    private val measurer = FakeMemoTextMeasurer()
    private val engine = MemoTextLayoutEngine(measurer)

    init {
        test("pure cjk non-final line expands cjk character slots to container width") {
            val layout =
                engine.layout(
                    MemoTextLayoutInput(
                        text = "中文中文中文中文",
                        maxWidthPx = 44f,
                        baseLetterSpacingPx = 0f,
                    ),
                )

            val firstLine = layout.lines.first()
            (firstLine.isJustified) shouldBe true
            (firstLine.visualWidthPx) shouldBe ((44f) plusOrMinus (FLOAT_DELTA))
            (firstLine.expansionSlots.all { it.kind == MemoTextExpansionSlotKind.CjkCharacter }) shouldBe true
            (firstLine.expansionSlots.all { it.extraWidthPx > 0f }) shouldBe true
            (layout.lines.last().isJustified) shouldBe false
        }

        test("cjk dominant mixed cjk latin justifies without expanding inside latin words") {
            val layout =
                engine.layout(
                    MemoTextLayoutInput(
                        text = "今天设计中文 review 中文排版内容",
                        maxWidthPx = 110f,
                        baseLetterSpacingPx = 0f,
                    ),
                )

            val firstLine = layout.lines.first()
            (firstLine.isJustified) shouldBe true
            (firstLine.visualWidthPx) shouldBe ((110f) plusOrMinus (FLOAT_DELTA))
            (firstLine.runs.any { it.text == "review" }) shouldBe true
            (firstLine.expansionSlots.none { slot -> slot.leftText == "rev" || slot.rightText == "iew" }) shouldBe true
            (firstLine.expansionSlots.isNotEmpty()) shouldBe true
        }

        test("pure english prose keeps ragged right spacing instead of justifying spaces") {
            val layout =
                engine.layout(
                    MemoTextLayoutInput(
                        text = "alpha beta gamma delta",
                        maxWidthPx = 60f,
                        baseLetterSpacingPx = 0f,
                    ),
                )

            val firstLine = layout.lines.first()
            (firstLine.isJustified) shouldBe false
            (firstLine.expansionSlots.isEmpty()) shouldBe true
            (firstLine.visualWidthPx) shouldBe ((firstLine.naturalWidthPx) plusOrMinus (FLOAT_DELTA))
        }

        test("english dominant mixed prose keeps ragged right spacing") {
            val layout =
                engine.layout(
                    MemoTextLayoutInput(
                        text = "today review memo 设计",
                        maxWidthPx = 80f,
                        baseLetterSpacingPx = 0f,
                    ),
                )

            val firstLine = layout.lines.first()
            (firstLine.isJustified) shouldBe false
            (firstLine.expansionSlots.isEmpty()) shouldBe true
            (firstLine.visualWidthPx) shouldBe ((firstLine.naturalWidthPx) plusOrMinus (FLOAT_DELTA))
        }

        test("configured letter spacing participates in natural width without disabling justification") {
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

            (withSpacing.lines.first().isJustified) shouldBe true
            (withSpacing.lines.first().visualWidthPx) shouldBe ((44f) plusOrMinus (FLOAT_DELTA))
            (withSpacing.lines.first().naturalWidthPx > withoutSpacing.lines.first().naturalWidthPx) shouldBe true
        }

        test("punctuation and protected ranges are not used as expansion interiors") {
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
            (firstLine.isJustified) shouldBe true
            (firstLine.expansionSlots.none { it.leftText == "「" || it.rightText == "」" }) shouldBe true
            (firstLine.expansionSlots.none { it.boundaryOffset in 4..6 }) shouldBe true
        }

        test("oversized latin token uses forced character breaks instead of overflowing indefinitely") {
            val layout =
                engine.layout(
                    MemoTextLayoutInput(
                        text = "supercalifragilistic",
                        maxWidthPx = 25f,
                        baseLetterSpacingPx = 0f,
                    ),
                )

            (layout.lines.size > 1) shouldBe true
            (layout.lines.any { it.wasForcedBreak }) shouldBe true
            (layout.lines.all { it.visualWidthPx <= 25f + FLOAT_DELTA }) shouldBe true
        }

        test("ellipsis replaces hidden content on final visible line") {
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
            (line.isJustified) shouldBe false
            (line.runs.joinToString(separator = "") { it.text }.length < layout.text.length) shouldBe true
            (line.ellipsis?.widthPx ?: 0f) shouldBe ((5f) plusOrMinus (FLOAT_DELTA))
            (line.visualWidthPx <= 44f + FLOAT_DELTA) shouldBe true
        }

        test("finite parent height caps collapsed preview layout lines") {
            val resolved =
                resolveMemoTextLayoutMaxLines(
                    requestedMaxLines = Int.MAX_VALUE,
                    maxHeightPx = 72,
                    lineHeightPx = 24f,
                )

            (resolved) shouldBe (3)
        }

        test("finite parent height never expands explicit max lines") {
            val resolved =
                resolveMemoTextLayoutMaxLines(
                    requestedMaxLines = 2,
                    maxHeightPx = 240,
                    lineHeightPx = 24f,
                )

            (resolved) shouldBe (2)
        }

        test("unbounded parent height keeps requested max lines") {
            val resolved =
                resolveMemoTextLayoutMaxLines(
                    requestedMaxLines = Int.MAX_VALUE,
                    maxHeightPx = Int.MAX_VALUE,
                    lineHeightPx = 24f,
                )

            (resolved) shouldBe (Int.MAX_VALUE)
        }

        test("tiny finite parent height keeps one reachable line") {
            val resolved =
                resolveMemoTextLayoutMaxLines(
                    requestedMaxLines = Int.MAX_VALUE,
                    maxHeightPx = 1,
                    lineHeightPx = 24f,
                )

            (resolved) shouldBe (1)
        }
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
