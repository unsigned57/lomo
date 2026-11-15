package com.lomo.ui.text

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

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
    typefaceResolver: MemoSpanTypefaceResolver? = null,
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
        typefaceResolver = typefaceResolver,
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
    val highlightCornerRadius = SEARCH_HIGHLIGHT_CORNER_RADIUS.toPx()
    layout.lines.forEach { line ->
        var selectionMinX = Float.MAX_VALUE
        var selectionMaxX = Float.MIN_VALUE
        var hasSelection = false

        val glyphBackgrounds = mutableListOf<Pair<MemoTextGlyph, Color>>()
        line.forEachGlyph(annotatedText.text, measurer, baseLetterSpacingPx) { glyph ->
            val backgroundColor = annotatedText.resolveBackgroundColor(glyph.start)
            if (backgroundColor != Color.Unspecified) {
                glyphBackgrounds += glyph to backgroundColor
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

        // One rounded rect per contiguous background run: per-glyph rects leave vertical seams,
        // and a rounded outline keeps the highlight visually attached to the text run.
        mergeGlyphBackgroundRuns(glyphBackgrounds).forEach { run ->
            drawRoundRect(
                color = run.color,
                topLeft = Offset(run.startXPx, line.topPx),
                size =
                Size(
                    width = run.endXPx - run.startXPx,
                    height = line.bottomPx - line.topPx,
                ),
                cornerRadius = CornerRadius(highlightCornerRadius, highlightCornerRadius),
            )
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
    typefaceResolver: MemoSpanTypefaceResolver? = null,
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
                        typefaceResolver = typefaceResolver,
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
                        typefaceResolver = typefaceResolver,
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

private fun MemoTextLine.ellipsisStyleOffset(): Int = runs
    .lastOrNull()
    ?.let { run -> (run.end - 1).coerceAtLeast(0) }
    ?: 0

/**
 * One contiguous background strip on a single line: glyphs that share a resolved background color
 * and are contiguous merge into one run so the renderer draws a single rounded rectangle instead
 * of per-glyph tiles.
 */
internal data class GlyphBackgroundRun(
    val startXPx: Float,
    val endXPx: Float,
    val color: Color,
    val startOffset: Int,
    val endOffset: Int,
)

internal fun mergeGlyphBackgroundRuns(glyphsWithColor: List<Pair<MemoTextGlyph, Color>>): List<GlyphBackgroundRun> {
    val runs = mutableListOf<GlyphBackgroundRun>()
    var currentColor: Color? = null
    var runStartX = 0f
    var runEndX = 0f
    var runStartOffset = 0
    var runEndOffset = 0
    glyphsWithColor.forEach { (glyph, color) ->
        if (currentColor == color && glyph.start == runEndOffset) {
            runEndX = glyph.xPx + glyph.widthPx
            runEndOffset = glyph.end
        } else {
            currentColor?.let { flushColor ->
                runs +=
                    GlyphBackgroundRun(
                        startXPx = runStartX,
                        endXPx = runEndX,
                        color = flushColor,
                        startOffset = runStartOffset,
                        endOffset = runEndOffset,
                    )
            }
            currentColor = color
            runStartX = glyph.xPx
            runEndX = glyph.xPx + glyph.widthPx
            runStartOffset = glyph.start
            runEndOffset = glyph.end
        }
    }
    currentColor?.let { flushColor ->
        runs +=
            GlyphBackgroundRun(
                startXPx = runStartX,
                endXPx = runEndX,
                color = flushColor,
                startOffset = runStartOffset,
                endOffset = runEndOffset,
            )
    }
    return runs
}

private val SEARCH_HIGHLIGHT_CORNER_RADIUS = 4.dp
