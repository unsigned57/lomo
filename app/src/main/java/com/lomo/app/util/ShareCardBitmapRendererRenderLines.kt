package com.lomo.app.util

import android.graphics.Bitmap
import android.text.TextPaint
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
            text = bodyLine.text,
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
