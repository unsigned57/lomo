package com.lomo.app.util

import android.graphics.Bitmap
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import com.lomo.app.feature.preferences.ShareCardTypefaces
import kotlin.math.max
import kotlin.math.roundToInt

private const val DEFAULT_SHARE_CARD_HIGHLIGHT_COLOR = 0x66FFE082

internal fun createBlankRenderLine(
    bodyLine: ShareBodyLine,
    spec: ShareCardLayoutSpec,
    paintSet: ShareCardPaintSet,
): ShareCardRenderLine = ShareCardRenderLine(
    type = bodyLine.type,
    layout = buildStaticLayout(BLANK_LAYOUT_TEXT, paintSet.paragraphPaint, spec.contentWidth),
    height = spec.lineSpacing,
)

internal fun createCodeRenderLine(
    bodyLine: ShareBodyLine,
    spec: ShareCardLayoutSpec,
    paintSet: ShareCardPaintSet,
): ShareCardRenderLine {
    val codeWidth =
        max(MIN_RENDER_DIMENSION_PX, spec.contentWidth - (spec.codeHorizontalPadding * 2).roundToInt())
    val layout = buildStaticLayout(bodyLine.text, paintSet.codePaint, codeWidth)
    return ShareCardRenderLine(
        type = bodyLine.type,
        layout = layout,
        height = layout.height + spec.codeVerticalPadding * 2,
    )
}

internal fun resolveShareCardQuoteLayoutStyle(spec: ShareCardLayoutSpec): ShareCardQuoteLayoutStyle {
    val textStartOffset = spec.quoteIndicatorWidth + spec.quoteTextStartPadding
    return ShareCardQuoteLayoutStyle(
        indicatorWidth = spec.quoteIndicatorWidth,
        indicatorCornerRadius = spec.quoteIndicatorCornerRadius,
        textStartOffset = textStartOffset,
        textWidth = max(MIN_RENDER_DIMENSION_PX, spec.contentWidth - textStartOffset.roundToInt()),
    )
}

internal fun createQuoteRenderLine(
    bodyLine: ShareBodyLine,
    spec: ShareCardLayoutSpec,
    paintSet: ShareCardPaintSet,
): ShareCardRenderLine {
    val quoteStyle = resolveShareCardQuoteLayoutStyle(spec)
    val layout =
        buildStaticLayout(
            text = bodyLine.withoutQuoteTextPrefix().toStyledText(paintSet.linkColor, paintSet.bodyTypefaces),
            paint = paintSet.quotePaint,
            width = quoteStyle.textWidth,
            lineSpacingMultiplier = spec.bodyLineSpacingMultiplier,
        )
    return ShareCardRenderLine(bodyLine.type, layout, layout.height.toFloat())
}

internal fun createTextRenderLine(
    bodyLine: ShareBodyLine,
    paint: TextPaint,
    spec: ShareCardLayoutSpec,
    shouldUseCenteredBody: Boolean = false,
    linkColor: Int? = null,
    bodyTypefaces: ShareCardTypefaces? = null,
): ShareCardRenderLine {
    val paragraphLayoutPolicy =
        resolveShareCardParagraphLayoutPolicy(
            text = bodyLine.text,
            shouldUseCenteredBody = shouldUseCenteredBody,
        )
    val layout =
        buildStaticLayout(
            text = bodyLine.toStyledText(linkColor, bodyTypefaces),
            paint = paint,
            width = spec.contentWidth,
            paragraphLayoutPolicy = paragraphLayoutPolicy,
            lineSpacingMultiplier = spec.bodyLineSpacingMultiplier,
        )
    return ShareCardRenderLine(bodyLine.type, layout, layout.height.toFloat())
}

internal fun createImageRenderLine(
    bodyLine: ShareBodyLine,
    imagePlaceholder: String,
    spec: ShareCardLayoutSpec,
    paintSet: ShareCardPaintSet,
    loadedImages: Map<Int, Bitmap>,
): ShareCardRenderLine {
    val bitmap = loadedImages[bodyLine.imageIndex]
    if (bitmap == null) {
        val layout = buildStaticLayout(imagePlaceholder, paintSet.paragraphPaint, spec.contentWidth)
        return ShareCardRenderLine(ShareBodyLineType.Paragraph, layout, layout.height.toFloat())
    }

    val scale = spec.contentWidth.toFloat() / bitmap.width
    val drawHeight = (bitmap.height * scale).coerceAtMost(spec.maxImageHeightPx)
    return ShareCardRenderLine(
        type = ShareBodyLineType.Image,
        layout = buildStaticLayout(BLANK_LAYOUT_TEXT, paintSet.paragraphPaint, spec.contentWidth),
        height = drawHeight + spec.imageVerticalPadding * 2,
        imageBitmap = bitmap,
        imageDrawWidth = spec.contentWidth.toFloat(),
        imageDrawHeight = drawHeight,
    )
}

internal data class ShareCardInlineFontRun(
    val start: Int,
    val end: Int,
    val typeface: Typeface?,
    val fallbackStyle: Int?,
)

internal fun resolveShareCardInlineFontRuns(
    textLength: Int,
    inlineStyles: List<ShareInlineStyleRange>,
    bodyTypefaces: ShareCardTypefaces?,
): List<ShareCardInlineFontRun> {
    if (textLength <= 0 || inlineStyles.isEmpty()) return emptyList()
    val boldRanges = mutableListOf<IntRange>()
    val italicRanges = mutableListOf<IntRange>()
    val boundaries = sortedSetOf<Int>()
    for (range in inlineStyles) {
        val start = range.start.coerceAtLeast(0)
        val end = range.end.coerceAtMost(textLength)
        if (start < end) {
            when (range.kind) {
                ShareInlineStyleKind.Bold -> {
                    boldRanges += start..end
                    boundaries += start
                    boundaries += end
                }

                ShareInlineStyleKind.Italic -> {
                    italicRanges += start..end
                    boundaries += start
                    boundaries += end
                }

                else -> Unit
            }
        }
    }
    if (boundaries.size < 2) return emptyList()
    val rawRuns = mutableListOf<ShareCardInlineFontRun>()
    boundaries.toList().zipWithNext { segStart, segEnd ->
        val style = if (segStart < segEnd) {
            val hasBold = boldRanges.any { segStart >= it.first && segEnd <= it.last }
            val hasItalic = italicRanges.any { segStart >= it.first && segEnd <= it.last }
            resolveSegmentFontStyle(hasBold, hasItalic, bodyTypefaces)
        } else {
            null
        }
        if (style != null) {
            val (typeface, fallbackStyle) = style
            val prev = rawRuns.lastOrNull()
            if (prev != null && prev.end == segStart && prev.typeface === typeface &&
                prev.fallbackStyle == fallbackStyle
            ) {
                rawRuns[rawRuns.lastIndex] = prev.copy(end = segEnd)
            } else {
                rawRuns += ShareCardInlineFontRun(segStart, segEnd, typeface, fallbackStyle)
            }
        }
    }
    return rawRuns
}

private fun resolveSegmentFontStyle(
    hasBold: Boolean,
    hasItalic: Boolean,
    bodyTypefaces: ShareCardTypefaces?,
): Pair<Typeface?, Int?>? = when {
    hasBold && hasItalic ->
        when {
            bodyTypefaces?.boldItalic != null -> bodyTypefaces.boldItalic to null
            bodyTypefaces?.bold != null -> bodyTypefaces.bold to Typeface.ITALIC
            bodyTypefaces?.italic != null -> bodyTypefaces.italic to Typeface.BOLD
            else -> null to Typeface.BOLD_ITALIC
        }

    hasBold ->
        if (bodyTypefaces?.bold != null) bodyTypefaces.bold to null else null to Typeface.BOLD

    hasItalic ->
        if (bodyTypefaces?.italic != null) bodyTypefaces.italic to null else null to Typeface.ITALIC

    else -> null
}

private class ShareCardTypefaceSpan(private val targetTypeface: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) {
        tp.typeface = targetTypeface
    }

    override fun updateMeasureState(textPaint: TextPaint) {
        textPaint.typeface = targetTypeface
    }
}

private fun ShareBodyLine.toStyledText(
    linkColor: Int? = null,
    bodyTypefaces: ShareCardTypefaces? = null,
): CharSequence {
    if (inlineStyles.isEmpty()) return text

    val styled = SpannableString(text)
    resolveShareCardInlineFontRuns(text.length, inlineStyles, bodyTypefaces).forEach { run ->
        run.typeface?.let { typeface ->
            styled.setSpan(ShareCardTypefaceSpan(typeface), run.start, run.end)
        }
        run.fallbackStyle?.let { fallbackStyle ->
            styled.setSpan(StyleSpan(fallbackStyle), run.start, run.end)
        }
    }
    inlineStyles.forEach { range ->
        if (range.start >= range.end || range.start < 0 || range.end > text.length) return@forEach
        when (range.kind) {
            ShareInlineStyleKind.Bold,
            ShareInlineStyleKind.Italic,
            -> Unit

            ShareInlineStyleKind.Strikethrough -> styled.setSpan(StrikethroughSpan(), range.start, range.end)

            ShareInlineStyleKind.InlineCode -> styled.setSpan(TypefaceSpan("monospace"), range.start, range.end)

            ShareInlineStyleKind.Link -> {
                styled.setSpan(UnderlineSpan(), range.start, range.end)
                if (linkColor != null) {
                    styled.setSpan(ForegroundColorSpan(linkColor), range.start, range.end)
                }
            }

            ShareInlineStyleKind.Underline -> styled.setSpan(UnderlineSpan(), range.start, range.end)

            ShareInlineStyleKind.Highlight -> styled.setSpan(
                android.text.style.BackgroundColorSpan(DEFAULT_SHARE_CARD_HIGHLIGHT_COLOR),
                range.start,
                range.end,
            )
        }
    }
    return styled
}

private fun ShareBodyLine.withoutQuoteTextPrefix(): ShareBodyLine {
    if (!text.startsWith(QUOTE_PREFIX)) return this
    val offset = QUOTE_PREFIX.length
    return copy(
        text = text.removePrefix(QUOTE_PREFIX),
        inlineStyles =
        inlineStyles.mapNotNull { range ->
            val shiftedStart = max(0, range.start - offset)
            val shiftedEnd = max(0, range.end - offset)
            if (shiftedStart >= shiftedEnd) {
                null
            } else {
                range.copy(start = shiftedStart, end = shiftedEnd)
            }
        },
    )
}

private fun SpannableString.setSpan(span: Any, start: Int, end: Int) {
    setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}
