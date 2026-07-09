package com.lomo.ui.text

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle

internal fun DrawScope.drawMemoTextLayout(
    annotatedText: AnnotatedString,
    baseStyle: TextStyle,
    layout: MemoTextLayout,
    measurer: MemoTextMeasurer,
    baseLetterSpacingPx: Float,
    selectionState: MemoTextSelectionState,
    selectionHighlightColor: Color,
    defaultLinkColor: Color,
    customTypeface: android.graphics.Typeface? = null,
) {
    drawMemoTextBackgrounds(
        annotatedText = annotatedText,
        layout = layout,
        measurer = measurer,
        baseLetterSpacingPx = baseLetterSpacingPx,
        selectionState = selectionState,
        selectionHighlightColor = selectionHighlightColor,
    )
    drawMemoTextGlyphs(
        annotatedText = annotatedText,
        baseStyle = baseStyle,
        layout = layout,
        measurer = measurer,
        baseLetterSpacingPx = baseLetterSpacingPx,
        defaultLinkColor = defaultLinkColor,
        customTypeface = customTypeface,
    )
}

private fun DrawScope.drawMemoTextBackgrounds(
    annotatedText: AnnotatedString,
    layout: MemoTextLayout,
    measurer: MemoTextMeasurer,
    baseLetterSpacingPx: Float,
    selectionState: MemoTextSelectionState,
    selectionHighlightColor: Color,
) {
    val selectedRange = selectionState.selectedRange
    layout.lines.forEach { line ->
        var selectionMinX = Float.MAX_VALUE
        var selectionMaxX = Float.MIN_VALUE
        var hasSelection = false

        line.forEachGlyph(annotatedText.text, measurer, baseLetterSpacingPx) { glyph ->
            val backgroundColor = annotatedText.resolveBackgroundColor(glyph.start)
            if (backgroundColor != Color.Unspecified) {
                drawGlyphBackground(line, glyph, backgroundColor)
            }
            if (selectedRange?.contains(glyph.start) == true) {
                hasSelection = true
                if (glyph.xPx < selectionMinX) {
                    selectionMinX = glyph.xPx
                }
                val glyphMaxX = glyph.xPx + glyph.widthPx
                if (glyphMaxX > selectionMaxX) {
                    selectionMaxX = glyphMaxX
                }
            }
        }

        if (hasSelection) {
            drawRect(
                color = selectionHighlightColor,
                topLeft = Offset(selectionMinX, line.topPx),
                size = Size(width = selectionMaxX - selectionMinX, height = line.bottomPx - line.topPx),
            )
        }
    }
}

private fun DrawScope.drawMemoTextGlyphs(
    annotatedText: AnnotatedString,
    baseStyle: TextStyle,
    layout: MemoTextLayout,
    measurer: MemoTextMeasurer,
    baseLetterSpacingPx: Float,
    defaultLinkColor: Color,
    customTypeface: android.graphics.Typeface? = null,
) {
    drawIntoCanvas { canvas ->
        layout.lines.forEach { line ->
            line.forEachGlyph(annotatedText.text, measurer, baseLetterSpacingPx) { glyph ->
                if (glyph.isWhitespace) return@forEachGlyph
                val paint =
                    baseStyle.toGlyphTextPaint(
                        annotatedText = annotatedText,
                        offset = glyph.start,
                        defaultLinkColor = defaultLinkColor,
                        density = this,
                        customTypeface = customTypeface,
                    )
                canvas.nativeCanvas.drawText(
                    annotatedText.text,
                    glyph.start,
                    glyph.end,
                    glyph.xPx,
                    line.baselinePx,
                    paint,
                )
            }
            line.ellipsis?.let { ellipsis ->
                val paint =
                    baseStyle.toGlyphTextPaint(
                        annotatedText = annotatedText,
                        offset = line.ellipsisStyleOffset(),
                        defaultLinkColor = defaultLinkColor,
                        density = this,
                        customTypeface = customTypeface,
                    )
                canvas.nativeCanvas.drawText(
                    MEMO_TEXT_ELLIPSIS,
                    ellipsis.xPx,
                    line.baselinePx,
                    paint,
                )
            }
        }
    }
}

private fun MemoTextLine.ellipsisStyleOffset(): Int =
    runs
        .lastOrNull()
        ?.let { run -> (run.end - 1).coerceAtLeast(0) }
        ?: 0

private fun DrawScope.drawGlyphBackground(
    line: MemoTextLine,
    glyph: MemoTextGlyph,
    color: Color,
) {
    drawRect(
        color = color,
        topLeft = Offset(glyph.xPx, line.topPx),
        size = Size(width = glyph.widthPx, height = line.bottomPx - line.topPx),
    )
}
