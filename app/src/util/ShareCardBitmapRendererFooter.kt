package com.lomo.app.util

import android.graphics.Canvas
import android.graphics.Paint

internal fun drawFooter(
    canvas: Canvas,
    contentLeft: Float,
    spec: ShareCardLayoutSpec,
    footerPaint: android.text.TextPaint,
    footerTop: Float,
    footer: ShareCardFooterContent,
    palette: ShareCardPalette,
) {
    val row = footer.row ?: return
    val dividerY = footerTop + spec.dividerTopSpacing
    val dividerPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.divider
            strokeWidth = spec.dividerStrokeWidth
        }
    canvas.drawLine(contentLeft, dividerY, contentLeft + spec.contentWidth, dividerY, dividerPaint)

    val rowTop = dividerY + spec.footerRowTopSpacing
    val baseline = rowTop - footerPaint.fontMetrics.ascent
    drawFooterRowText(canvas, contentLeft, spec, footerPaint, baseline, row)
}

private fun drawFooterRowText(
    canvas: Canvas,
    contentLeft: Float,
    spec: ShareCardLayoutSpec,
    footerPaint: android.text.TextPaint,
    baseline: Float,
    row: ShareCardFooterRow,
) {
    if (row.startText.isNotBlank()) {
        canvas.drawText(row.startText, contentLeft, baseline, footerPaint)
    }
    if (row.centerText.isNotBlank()) {
        val centerWidth = footerPaint.measureText(row.centerText)
        canvas.drawText(
            row.centerText,
            contentLeft + (spec.contentWidth - centerWidth) / 2f,
            baseline,
            footerPaint,
        )
    }
    if (row.endText.isNotBlank()) {
        val endWidth = footerPaint.measureText(row.endText)
        canvas.drawText(
            row.endText,
            contentLeft + spec.contentWidth - endWidth,
            baseline,
            footerPaint,
        )
    }
}
