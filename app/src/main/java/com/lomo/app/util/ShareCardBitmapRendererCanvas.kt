package com.lomo.app.util

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import kotlin.math.max
import kotlin.math.roundToInt

internal fun createShareCardLayoutSpec(resources: Resources): ShareCardLayoutSpec {
    val canvasWidth =
        (resources.displayMetrics.widthPixels.coerceAtLeast(MIN_SHARE_CARD_CANVAS_WIDTH_PX) *
            SHARE_CARD_CANVAS_WIDTH_RATIO).roundToInt()
    val outerPadding = dp(resources, OUTER_PADDING_DP)
    val cardPadding = dp(resources, CARD_PADDING_DP)
    val contentWidth =
        (canvasWidth - outerPadding * 2 - cardPadding * 2)
            .roundToInt()
            .coerceAtLeast(MIN_RENDER_DIMENSION_PX)

    return ShareCardLayoutSpec(
        canvasWidth = canvasWidth,
        outerPadding = outerPadding,
        cardPadding = cardPadding,
        cardCorner = dp(resources, CARD_CORNER_DP),
        lineSpacing = dp(resources, LINE_SPACING_DP),
        tagBottomSpacing = dp(resources, TAG_BOTTOM_SPACING_DP),
        titleBottomSpacing = dp(resources, TITLE_BOTTOM_SPACING_DP),
        codeHorizontalPadding = dp(resources, CODE_HORIZONTAL_PADDING_DP),
        codeVerticalPadding = dp(resources, CODE_VERTICAL_PADDING_DP),
        codeCorner = dp(resources, CODE_CORNER_DP),
        imageCorner = dp(resources, IMAGE_CORNER_DP),
        imageVerticalPadding = dp(resources, IMAGE_VERTICAL_PADDING_DP),
        maxImageHeightPx = dp(resources, MAX_IMAGE_HEIGHT_DP),
        dividerTopSpacing = dp(resources, FOOTER_DIVIDER_TOP_SPACING_DP),
        dividerStrokeWidth = dp(resources, FOOTER_DIVIDER_STROKE_DP),
        footerRowTopSpacing = dp(resources, FOOTER_ROW_TOP_SPACING_DP),
        minCardHeight = dp(resources, MIN_CARD_HEIGHT_DP),
        contentWidth = contentWidth,
    )
}

internal fun buildShareCardComposition(
    displayTags: List<String>,
    title: String?,
    bodyLines: List<ShareBodyLine>,
    imagePlaceholder: String,
    spec: ShareCardLayoutSpec,
    paintSet: ShareCardPaintSet,
    loadedImages: Map<Int, Bitmap>,
    showFooter: Boolean,
    shouldUseCenteredBody: Boolean,
): ShareCardComposition {
    val tagLayout =
        displayTags
            .takeIf { it.isNotEmpty() }
            ?.joinToString(separator = TAG_JOIN_SEPARATOR) { "#$it" }
            ?.let { buildStaticLayout(it, paintSet.tagPaint, spec.contentWidth, maxLines = MAX_TAG_LINES) }
    val titleLayout =
        title?.let {
            buildStaticLayout(it, paintSet.titlePaint, spec.contentWidth, maxLines = MAX_TITLE_LINES)
        }
    val bodyRenderLines =
        bodyLines.map { bodyLine ->
            createRenderLine(
                bodyLine = bodyLine,
                imagePlaceholder = imagePlaceholder,
                spec = spec,
                paintSet = paintSet,
                loadedImages = loadedImages,
                shouldUseCenteredBody = shouldUseCenteredBody,
            )
        }
    val bodyContentHeight =
        bodyRenderLines.foldIndexed(0f) { index, total, line ->
            total + line.height + if (index != bodyRenderLines.lastIndex) spec.lineSpacing else 0f
        }
    val contentHeight =
        (tagLayout?.height?.toFloat()?.plus(spec.tagBottomSpacing) ?: 0f) +
            (titleLayout?.height?.toFloat()?.plus(spec.titleBottomSpacing) ?: 0f) +
            bodyContentHeight
    val footerBlockHeight = measureFooterBlockHeight(paintSet.footerPaint, spec, showFooter)
    val cardHeight =
        max(spec.minCardHeight, contentHeight + footerBlockHeight + spec.cardPadding * 2)
            .coerceAtMost(MAX_SHARE_BITMAP_HEIGHT_PX - spec.outerPadding * 2)

    return ShareCardComposition(
        tagLayout = tagLayout,
        titleLayout = titleLayout,
        bodyRenderLines = bodyRenderLines,
        bodyContentHeight = bodyContentHeight,
        footerBlockHeight = footerBlockHeight,
        bitmapHeight =
            (spec.outerPadding * 2 + cardHeight)
                .roundToInt()
                .coerceIn(MIN_RENDER_DIMENSION_PX, MAX_SHARE_BITMAP_HEIGHT_PX),
    )
}

internal fun renderShareCardBitmap(
    spec: ShareCardLayoutSpec,
    palette: ShareCardPalette,
    paintSet: ShareCardPaintSet,
    composition: ShareCardComposition,
    footer: ShareCardFooterContent,
    shouldUseCenteredBody: Boolean,
): Bitmap {
    val bitmap = Bitmap.createBitmap(spec.canvasWidth, composition.bitmapHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cardRect = drawBackgroundAndCard(canvas, spec, palette, composition.bitmapHeight)
    val contentLeft = cardRect.left + spec.cardPadding
    val footerTop =
        if (footer.showFooter) {
            cardRect.bottom - spec.cardPadding - composition.footerBlockHeight
        } else {
            cardRect.bottom - spec.cardPadding
        }
    var cursorY = drawHeaderLayouts(canvas, composition, contentLeft, cardRect.top + spec.cardPadding, spec)

    if (shouldUseCenteredBody) {
        val bodyAreaHeight = (footerTop - cursorY).coerceAtLeast(0f)
        if (composition.bodyContentHeight < bodyAreaHeight) {
            cursorY += (bodyAreaHeight - composition.bodyContentHeight) / 2f
        }
    }

    drawBodyLines(canvas, composition.bodyRenderLines, spec, contentLeft, cursorY, footerTop, palette)
    if (footer.showFooter) {
        drawFooter(canvas, contentLeft, spec, paintSet.footerPaint, footerTop, footer, palette)
    }
    return bitmap
}

private fun createRenderLine(
    bodyLine: ShareBodyLine,
    imagePlaceholder: String,
    spec: ShareCardLayoutSpec,
    paintSet: ShareCardPaintSet,
    loadedImages: Map<Int, Bitmap>,
    shouldUseCenteredBody: Boolean,
): ShareCardRenderLine =
    when (bodyLine.type) {
        ShareBodyLineType.Blank ->
            ShareCardRenderLine(
                type = bodyLine.type,
                layout = buildStaticLayout(BLANK_LAYOUT_TEXT, paintSet.paragraphPaint, spec.contentWidth),
                height = spec.lineSpacing,
            )
        ShareBodyLineType.Code -> {
            val codeWidth =
                max(MIN_RENDER_DIMENSION_PX, spec.contentWidth - (spec.codeHorizontalPadding * 2).roundToInt())
            val layout = buildStaticLayout(bodyLine.text, paintSet.codePaint, codeWidth)
            ShareCardRenderLine(
                type = bodyLine.type,
                layout = layout,
                height = layout.height + spec.codeVerticalPadding * 2,
            )
        }
        ShareBodyLineType.Quote -> {
            val layout = buildStaticLayout(bodyLine.text, paintSet.quotePaint, spec.contentWidth)
            ShareCardRenderLine(bodyLine.type, layout, layout.height.toFloat())
        }
        ShareBodyLineType.Bullet -> {
            val layout = buildStaticLayout(bodyLine.text, paintSet.bulletPaint, spec.contentWidth)
            ShareCardRenderLine(bodyLine.type, layout, layout.height.toFloat())
        }
        ShareBodyLineType.Image -> {
            val bitmap = loadedImages[bodyLine.imageIndex]
            if (bitmap == null) {
                val layout = buildStaticLayout(imagePlaceholder, paintSet.paragraphPaint, spec.contentWidth)
                ShareCardRenderLine(ShareBodyLineType.Paragraph, layout, layout.height.toFloat())
            } else {
                val scale = spec.contentWidth.toFloat() / bitmap.width
                val drawHeight = (bitmap.height * scale).coerceAtMost(spec.maxImageHeightPx)
                ShareCardRenderLine(
                    type = ShareBodyLineType.Image,
                    layout = buildStaticLayout(BLANK_LAYOUT_TEXT, paintSet.paragraphPaint, spec.contentWidth),
                    height = drawHeight + spec.imageVerticalPadding * 2,
                    imageBitmap = bitmap,
                    imageDrawWidth = spec.contentWidth.toFloat(),
                    imageDrawHeight = drawHeight,
                )
            }
        }
        ShareBodyLineType.Paragraph -> {
            val alignment =
                if (shouldUseCenteredBody) {
                    Layout.Alignment.ALIGN_CENTER
                } else {
                    Layout.Alignment.ALIGN_NORMAL
                }
            val layout =
                buildStaticLayout(
                    text = bodyLine.text,
                    paint = paintSet.paragraphPaint,
                    width = spec.contentWidth,
                    alignment = alignment,
                )
            ShareCardRenderLine(bodyLine.type, layout, layout.height.toFloat())
        }
    }

private fun measureFooterBlockHeight(
    footerPaint: android.text.TextPaint,
    spec: ShareCardLayoutSpec,
    showFooter: Boolean,
): Float {
    if (!showFooter) {
        return 0f
    }

    val footerTextHeight = footerPaint.fontMetrics.descent - footerPaint.fontMetrics.ascent
    return spec.dividerTopSpacing + spec.dividerStrokeWidth + spec.footerRowTopSpacing + footerTextHeight
}

private fun drawBackgroundAndCard(
    canvas: Canvas,
    spec: ShareCardLayoutSpec,
    palette: ShareCardPalette,
    bitmapHeight: Int,
): RectF {
    val backgroundPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader =
                LinearGradient(
                    0f,
                    0f,
                    spec.canvasWidth.toFloat(),
                    bitmapHeight.toFloat(),
                    palette.bgStart,
                    palette.bgEnd,
                    Shader.TileMode.CLAMP,
                )
        }
    canvas.drawRect(0f, 0f, spec.canvasWidth.toFloat(), bitmapHeight.toFloat(), backgroundPaint)

    val cardRect =
        RectF(
            spec.outerPadding,
            spec.outerPadding,
            spec.canvasWidth - spec.outerPadding,
            bitmapHeight - spec.outerPadding,
        )
    val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.card }
    val borderPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.cardBorder
            strokeWidth = spec.dividerStrokeWidth
            style = Paint.Style.STROKE
        }
    canvas.drawRoundRect(cardRect, spec.cardCorner, spec.cardCorner, cardPaint)
    canvas.drawRoundRect(cardRect, spec.cardCorner, spec.cardCorner, borderPaint)
    return cardRect
}

private fun drawHeaderLayouts(
    canvas: Canvas,
    composition: ShareCardComposition,
    contentLeft: Float,
    startY: Float,
    spec: ShareCardLayoutSpec,
): Float {
    var cursorY = startY

    composition.tagLayout?.let { tagLayout ->
        drawLayout(canvas, tagLayout, contentLeft, cursorY)
        cursorY += tagLayout.height + spec.tagBottomSpacing
    }
    composition.titleLayout?.let { titleLayout ->
        drawLayout(canvas, titleLayout, contentLeft, cursorY)
        cursorY += titleLayout.height + spec.titleBottomSpacing
    }

    return cursorY
}

private fun drawBodyLines(
    canvas: Canvas,
    bodyRenderLines: List<ShareCardRenderLine>,
    spec: ShareCardLayoutSpec,
    contentLeft: Float,
    startY: Float,
    footerTop: Float,
    palette: ShareCardPalette,
) {
    val codeBackgroundPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.tagBg
            alpha = CODE_BACKGROUND_ALPHA
        }
    val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    var cursorY = startY

    canvas.save()
    canvas.clipRect(contentLeft, cursorY, contentLeft + spec.contentWidth, footerTop)
    bodyRenderLines.forEachIndexed { index, line ->
        when (line.type) {
            ShareBodyLineType.Blank -> cursorY += line.height
            ShareBodyLineType.Code -> {
                drawCodeLine(canvas, line, spec, contentLeft, cursorY, codeBackgroundPaint)
                cursorY += line.height
            }
            ShareBodyLineType.Image -> {
                drawImageLine(canvas, line, spec, contentLeft, cursorY, imagePaint)
                cursorY += line.height
            }
            else -> {
                drawLayout(canvas, line.layout, contentLeft, cursorY)
                cursorY += line.height
            }
        }
        if (index != bodyRenderLines.lastIndex) {
            cursorY += spec.lineSpacing
        }
    }
    canvas.restore()
}

private fun drawCodeLine(
    canvas: Canvas,
    line: ShareCardRenderLine,
    spec: ShareCardLayoutSpec,
    contentLeft: Float,
    cursorY: Float,
    codeBackgroundPaint: Paint,
) {
    val codeRect =
        RectF(
            contentLeft,
            cursorY,
            contentLeft + spec.contentWidth,
            cursorY + line.height,
        )
    canvas.drawRoundRect(codeRect, spec.codeCorner, spec.codeCorner, codeBackgroundPaint)
    drawLayout(
        canvas = canvas,
        layout = line.layout,
        x = contentLeft + spec.codeHorizontalPadding,
        y = cursorY + spec.codeVerticalPadding,
    )
}

private fun drawImageLine(
    canvas: Canvas,
    line: ShareCardRenderLine,
    spec: ShareCardLayoutSpec,
    contentLeft: Float,
    cursorY: Float,
    imagePaint: Paint,
) {
    val bitmap = line.imageBitmap ?: return
    val top = cursorY + spec.imageVerticalPadding
    val destinationRect =
        RectF(
            contentLeft,
            top,
            contentLeft + line.imageDrawWidth,
            top + line.imageDrawHeight,
        )
    val clipPath = Path().apply {
        addRoundRect(destinationRect, spec.imageCorner, spec.imageCorner, Path.Direction.CW)
    }

    canvas.save()
    canvas.clipPath(clipPath)
    canvas.drawBitmap(bitmap, null, destinationRect, imagePaint)
    canvas.restore()
}

private fun drawFooter(
    canvas: Canvas,
    contentLeft: Float,
    spec: ShareCardLayoutSpec,
    footerPaint: android.text.TextPaint,
    footerTop: Float,
    footer: ShareCardFooterContent,
    palette: ShareCardPalette,
) {
    val dividerY = footerTop + spec.dividerTopSpacing
    val dividerPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.divider
            strokeWidth = spec.dividerStrokeWidth
        }
    canvas.drawLine(contentLeft, dividerY, contentLeft + spec.contentWidth, dividerY, dividerPaint)

    val rowTop = dividerY + spec.footerRowTopSpacing
    val baseline = rowTop - footerPaint.fontMetrics.ascent
    if (footer.showTime) {
        canvas.drawText(footer.createdAtText, contentLeft, baseline, footerPaint)
    }
    if (footer.activeDayCountText.isNotBlank()) {
        val textWidth = footerPaint.measureText(footer.activeDayCountText)
        canvas.drawText(
            footer.activeDayCountText,
            contentLeft + spec.contentWidth - textWidth,
            baseline,
            footerPaint,
        )
    }
}
