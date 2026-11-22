package com.lomo.ui.text

import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit

internal class AnnotatedMemoTextMeasurer(
    private val annotatedText: AnnotatedString,
    baseStyle: TextStyle,
    density: Density,
    private val basePaint: TextPaint,
    customTypeface: Typeface? = null,
    typefaceResolver: MemoSpanTypefaceResolver,
) : MemoTextMeasurer {
    private val runs: List<SpanMeasureRun> =
        buildSpanMeasureRuns(
            annotatedText = annotatedText,
            baseStyle = baseStyle,
            density = density,
            basePaint = basePaint,
            customTypeface = customTypeface,
            typefaceResolver = typefaceResolver,
        )

    override fun measureText(text: String, start: Int, end: Int): Float {
        if (text.length != annotatedText.text.length || text != annotatedText.text || runs.isEmpty() || start >= end) {
            return basePaint.measureText(text, start, end)
        }
        var totalWidth = 0f
        var cursor = start
        var runIndex = 0
        while (runIndex < runs.size && cursor < end) {
            val run = runs[runIndex]
            if (run.end > cursor && run.start < end) {
                if (cursor < run.start) {
                    totalWidth += basePaint.measureText(text, cursor, run.start)
                    cursor = run.start
                }
                val sliceEnd = minOf(end, run.end)
                totalWidth += run.paint.measureText(text, cursor, sliceEnd)
                cursor = sliceEnd
            }
            runIndex++
        }
        if (cursor < end) {
            totalWidth += basePaint.measureText(text, cursor, end)
        }
        return totalWidth
    }
}

private data class MemoSpanPaintKey(
    val fontFamily: FontFamily?,
    val fontWeight: FontWeight,
    val fontStyle: FontStyle,
    val fontSizePx: Float,
)

private data class SpanMeasureRun(val start: Int, val end: Int, val paint: TextPaint)

private fun buildSpanMeasureRuns(
    annotatedText: AnnotatedString,
    baseStyle: TextStyle,
    density: Density,
    basePaint: TextPaint,
    customTypeface: Typeface?,
    typefaceResolver: MemoSpanTypefaceResolver,
): List<SpanMeasureRun> {
    if (annotatedText.spanStyles.isEmpty() || annotatedText.isEmpty()) return emptyList()
    val boundaries = collectSpanBoundaryOffsets(annotatedText)
    if (boundaries.size < 2) return emptyList()

    val baseKey = baseStyle.toBasePaintKey(density, basePaint.textSize)
    val paintCache = HashMap<MemoSpanPaintKey, TextPaint>()
    val runs = mutableListOf<SpanMeasureRun>()
    var previousEnd = boundaries.first()
    for (index in 1 until boundaries.size) {
        val segStart = previousEnd
        val segEnd = boundaries[index]
        previousEnd = segEnd
        if (segStart < segEnd) {
            val spanStyle = annotatedText.resolveSpanStyle(segStart)
            val key = baseStyle.toSpanPaintKey(spanStyle, baseKey, density)
            if (key != baseKey) {
                val runPaint =
                    resolveOrCreateSpanPaint(
                        paintCache = paintCache,
                        key = key,
                        baseStyle = baseStyle,
                        spanStyle = spanStyle,
                        basePaint = basePaint,
                        customTypeface = customTypeface,
                        typefaceResolver = typefaceResolver,
                    )
                appendOrCoalesceRun(runs, segStart, segEnd, runPaint)
            }
        }
    }
    return runs
}

private fun resolveOrCreateSpanPaint(
    paintCache: HashMap<MemoSpanPaintKey, TextPaint>,
    key: MemoSpanPaintKey,
    baseStyle: TextStyle,
    spanStyle: SpanStyle,
    basePaint: TextPaint,
    customTypeface: Typeface?,
    typefaceResolver: MemoSpanTypefaceResolver,
): TextPaint = paintCache.getOrPut(key) {
    TextPaint(basePaint).also { paint ->
        paint.textSize = key.fontSizePx
        paint.typeface = baseStyle.resolveMemoTypeface(spanStyle, customTypeface, typefaceResolver)
    }
}

private fun appendOrCoalesceRun(runs: MutableList<SpanMeasureRun>, segStart: Int, segEnd: Int, runPaint: TextPaint) {
    val last = runs.lastOrNull()
    if (last != null && last.end == segStart && last.paint === runPaint) {
        runs[runs.lastIndex] = last.copy(end = segEnd)
    } else {
        runs += SpanMeasureRun(start = segStart, end = segEnd, paint = runPaint)
    }
}

private fun TextStyle.toBasePaintKey(density: Density, fallbackSizePx: Float): MemoSpanPaintKey = MemoSpanPaintKey(
    fontFamily = fontFamily,
    fontWeight = fontWeight ?: FontWeight.Normal,
    fontStyle = fontStyle ?: FontStyle.Normal,
    fontSizePx = if (fontSize == TextUnit.Unspecified) fallbackSizePx else resolveFontSizePx(density),
)

private fun TextStyle.toSpanPaintKey(
    spanStyle: SpanStyle,
    baseKey: MemoSpanPaintKey,
    density: Density,
): MemoSpanPaintKey = MemoSpanPaintKey(
    fontFamily = spanStyle.fontFamily ?: fontFamily,
    fontWeight = spanStyle.fontWeight ?: baseKey.fontWeight,
    fontStyle = spanStyle.fontStyle ?: baseKey.fontStyle,
    fontSizePx = resolveSpanFontSizePx(spanStyle, density),
)

internal fun collectSpanBoundaryOffsets(annotatedText: AnnotatedString): List<Int> {
    val maxOffset = annotatedText.length
    val offsets = sortedSetOf<Int>()
    for (range in annotatedText.spanStyles) {
        val start = range.start.coerceIn(0, maxOffset)
        val end = range.end.coerceIn(0, maxOffset)
        if (start < end) {
            offsets += start
            offsets += end
        }
    }
    return offsets.toList()
}
