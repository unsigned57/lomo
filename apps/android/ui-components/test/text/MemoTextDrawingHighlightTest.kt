package com.lomo.ui.text

import androidx.compose.ui.graphics.Color
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: mergeGlyphBackgroundRuns in the ui-components text drawing layer.
 * - Owning layer: ui-components.
 * - Priority tier: P2.
 * - Capability: collapse consecutive same-color highlighted glyphs into one contiguous background
 *   run per line so the renderer draws a single rounded rectangle per run.
 *
 * Scenarios:
 * - Given consecutive glyphs sharing one background color, when runs merge, then one run spans
 *   from the first glyph's leading edge to the last glyph's trailing edge.
 * - Given two same-color glyph groups separated by an unhighlighted offset gap, when runs merge,
 *   then two runs preserve the visual gap.
 * - Given adjacent glyphs with different background colors, when runs merge, then each color
 *   produces its own run.
 *
 * Observable outcomes: run count, horizontal extents, colors, and source offsets of merged runs.
 * TDD proof: the per-glyph drawRect path could not express a merged run; this function is the
 *   production seam between glyph iteration and drawRoundRect.
 * Excludes: Canvas rasterization, Compose composition, and span resolution (covered by the
 *   annotation pipeline tests).
 */
class MemoTextDrawingHighlightTest : UiComponentsFunSpec() {
    init {
        test("consecutive same-color glyphs merge into one run covering the full extent") {
            val color = Color(0xFFAAFF00)
            val glyphs =
                listOf(
                    glyph(start = 0, end = 1, x = 10f, width = 8f) to color,
                    glyph(start = 1, end = 2, x = 18f, width = 8f) to color,
                    glyph(start = 2, end = 3, x = 26f, width = 9f) to color,
                )

            val runs = mergeGlyphBackgroundRuns(glyphs)

            runs.size shouldBe 1
            runs[0].startXPx shouldBe 10f
            runs[0].endXPx shouldBe 35f
            runs[0].color shouldBe color
            runs[0].startOffset shouldBe 0
            runs[0].endOffset shouldBe 3
        }

        test("an offset gap between same-color glyphs splits the highlight into two runs") {
            val color = Color(0xFF00AAFF)
            val glyphs =
                listOf(
                    glyph(start = 0, end = 1, x = 10f, width = 8f) to color,
                    glyph(start = 1, end = 2, x = 18f, width = 8f) to color,
                    // offset 2 belongs to an unhighlighted glyph and is absent from the list
                    glyph(start = 3, end = 4, x = 36f, width = 8f) to color,
                )

            val runs = mergeGlyphBackgroundRuns(glyphs)

            runs.size shouldBe 2
            runs[0].startOffset shouldBe 0
            runs[0].endOffset shouldBe 2
            runs[1].startOffset shouldBe 3
            runs[1].endOffset shouldBe 4
            runs[1].startXPx shouldBe 36f
        }

        test("adjacent glyphs with different colors produce separate runs") {
            val first = Color(0xFFAA00FF)
            val second = Color(0xFF00FFAA)
            val glyphs =
                listOf(
                    glyph(start = 0, end = 1, x = 10f, width = 8f) to first,
                    glyph(start = 1, end = 2, x = 18f, width = 8f) to second,
                )

            val runs = mergeGlyphBackgroundRuns(glyphs)

            runs.size shouldBe 2
            runs[0].color shouldBe first
            runs[1].color shouldBe second
        }

        test("empty input produces no runs") {
            mergeGlyphBackgroundRuns(emptyList()) shouldBe emptyList()
        }
    }

    private companion object {
        fun glyph(start: Int, end: Int, x: Float, width: Float): MemoTextGlyph = MemoTextGlyph(
            start = start,
            end = end,
            xPx = x,
            widthPx = width,
            isWhitespace = false,
        )
    }
}
