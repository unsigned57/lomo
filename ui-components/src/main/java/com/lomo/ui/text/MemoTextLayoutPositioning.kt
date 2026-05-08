package com.lomo.ui.text

import androidx.compose.ui.geometry.Offset

internal inline fun MemoTextLine.forEachGlyph(
    text: String,
    measurer: MemoTextMeasurer,
    baseLetterSpacingPx: Float,
    block: (MemoTextGlyph) -> Unit,
) {
    runs.forEach { run ->
        var x = run.xPx
        var index = run.start
        while (index < run.end) {
            val codePoint = Character.codePointAt(text, index)
            val next = index + Character.charCount(codePoint)
            val width = measurer.measureText(text, index, next)
            block(
                MemoTextGlyph(
                    start = index,
                    end = next,
                    xPx = x,
                    widthPx = width,
                    isWhitespace = Character.isWhitespace(codePoint),
                ),
            )
            x += width
            if (next < run.end) {
                x += baseLetterSpacingPx
            }
            index = next
        }
    }
}

internal fun MemoTextLayout.offsetForPosition(
    position: Offset,
    measurer: MemoTextMeasurer,
    baseLetterSpacingPx: Float,
): Int {
    if (lines.isEmpty()) return 0
    val line =
        lines.firstOrNull { position.y in it.topPx..it.bottomPx }
            ?: lines.minBy { line -> kotlin.math.abs(position.y - line.topPx) }
    var resolvedOffset = line.runs.lastOrNull()?.end ?: 0
    var found = false
    line.forEachGlyph(text, measurer, baseLetterSpacingPx) { glyph ->
        if (!found && position.x <= glyph.xPx + glyph.widthPx / 2f) {
            resolvedOffset = glyph.start
            found = true
        } else if (!found) {
            resolvedOffset = glyph.end
        }
    }
    return resolvedOffset.coerceIn(0, text.length)
}

internal fun MemoTextLayout.positionForOffset(
    offset: Int,
    measurer: MemoTextMeasurer,
    baseLetterSpacingPx: Float,
): Offset {
    if (lines.isEmpty()) return Offset.Zero
    lines.forEach { line ->
        line.forEachGlyph(text, measurer, baseLetterSpacingPx) { glyph ->
            if (offset <= glyph.start) {
                return Offset(glyph.xPx, line.bottomPx)
            }
            if (offset in (glyph.start + 1)..glyph.end) {
                return Offset(glyph.xPx + glyph.widthPx, line.bottomPx)
            }
        }
    }
    val lastLine = lines.last()
    return Offset(lastLine.visualWidthPx, lastLine.bottomPx)
}

internal fun MemoTextLayout.selectionRangeAtOffset(offset: Int): IntRange {
    lines.forEach { line ->
        line.runs.firstOrNull { run -> offset in run.start until run.end }?.let { run ->
            return when (run.kind) {
                MemoTextRunKind.Latin -> run.start until run.end
                MemoTextRunKind.Whitespace -> {
                    val start = (offset - 1).coerceAtLeast(0)
                    start until offset.coerceAtLeast(start + 1)
                }
                else -> selectSingleCodePoint(offset)
            }
        }
    }
    val safeOffset = offset.coerceIn(0, text.length)
    return safeOffset until (safeOffset + 1).coerceAtMost(text.length)
}

private fun MemoTextLayout.selectSingleCodePoint(offset: Int): IntRange {
    val start = offset.coerceIn(0, (text.length - 1).coerceAtLeast(0))
    val end = Character.offsetByCodePoints(text, start, 1).coerceAtMost(text.length)
    return start until end
}
