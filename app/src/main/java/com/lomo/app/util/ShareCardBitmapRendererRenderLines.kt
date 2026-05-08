package com.lomo.app.util

import android.graphics.Bitmap
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import kotlin.math.max
import kotlin.math.roundToInt

internal fun createBlankRenderLine(
    bodyLine: ShareBodyLine,
    spec: ShareCardLayoutSpec,
    paintSet: ShareCardPaintSet,
): ShareCardRenderLine =
    ShareCardRenderLine(
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
            text = bodyLine.withoutQuoteTextPrefix().toStyledText(),
            paint = paintSet.quotePaint,
            width = quoteStyle.textWidth,
        )
    return ShareCardRenderLine(bodyLine.type, layout, layout.height.toFloat())
}

internal fun createTextRenderLine(
    bodyLine: ShareBodyLine,
    paint: TextPaint,
    spec: ShareCardLayoutSpec,
    shouldUseCenteredBody: Boolean = false,
): ShareCardRenderLine {
    val paragraphLayoutPolicy =
        resolveShareCardParagraphLayoutPolicy(
            text = bodyLine.text,
            shouldUseCenteredBody = shouldUseCenteredBody,
        )
    val layout =
        buildStaticLayout(
            text = bodyLine.toStyledText(),
            paint = paint,
            width = spec.contentWidth,
            paragraphLayoutPolicy = paragraphLayoutPolicy,
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

private fun ShareBodyLine.toStyledText(): CharSequence {
    if (inlineStyles.isEmpty()) return text

    val styled = SpannableString(text)
    inlineStyles.forEach { range ->
        if (range.start >= range.end || range.start < 0 || range.end > text.length) return@forEach
        when (range.kind) {
            ShareInlineStyleKind.Bold -> styled.setSpan(StyleSpan(Typeface.BOLD), range.start, range.end)
            ShareInlineStyleKind.Italic -> styled.setSpan(StyleSpan(Typeface.ITALIC), range.start, range.end)
            ShareInlineStyleKind.Strikethrough -> styled.setSpan(StrikethroughSpan(), range.start, range.end)
            ShareInlineStyleKind.InlineCode -> styled.setSpan(TypefaceSpan("monospace"), range.start, range.end)
            ShareInlineStyleKind.Link -> styled.setSpan(UnderlineSpan(), range.start, range.end)
            ShareInlineStyleKind.Underline -> styled.setSpan(UnderlineSpan(), range.start, range.end)
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

private fun SpannableString.setSpan(
    span: Any,
    start: Int,
    end: Int,
) {
    setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}
