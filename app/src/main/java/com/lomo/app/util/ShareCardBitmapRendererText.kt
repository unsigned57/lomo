package com.lomo.app.util

import android.annotation.SuppressLint
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import androidx.core.graphics.withTranslation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun bodyTextSizeSp(textLengthWithoutMarkers: Int): Float =
    when {
        textLengthWithoutMarkers <= BODY_TEXT_SIZE_SHORT_THRESHOLD -> BODY_TEXT_SIZE_SHORT_SP
        textLengthWithoutMarkers <= BODY_TEXT_SIZE_MEDIUM_THRESHOLD -> BODY_TEXT_SIZE_MEDIUM_SP
        textLengthWithoutMarkers <= BODY_TEXT_SIZE_LONG_THRESHOLD -> BODY_TEXT_SIZE_LONG_SP
        else -> BODY_TEXT_SIZE_DEFAULT_SP
    }

internal fun shouldUseCenteredBody(
    input: ShareCardRenderInput,
    bodyLines: List<ShareBodyLine>,
): Boolean {
    val hasNoHeader = input.displayTags.isEmpty() && input.title == null
    val hasOnlyParagraphs =
        bodyLines.all { line ->
            line.type == ShareBodyLineType.Paragraph || line.type == ShareBodyLineType.Blank
        }
    val hasShortBody =
        input.textLengthWithoutMarkers <= SHORT_BODY_CENTER_THRESHOLD &&
            bodyLines.count { it.type != ShareBodyLineType.Blank } <= SHORT_BODY_MAX_NON_BLANK_LINES &&
            hasOnlyParagraphs

    return !input.hasImages && hasNoHeader && hasShortBody
}

internal fun createShareCardPaintSet(
    resources: Resources,
    palette: ShareCardPalette,
    bodyTextSizeSp: Float,
    shouldUseCenteredBody: Boolean,
): ShareCardPaintSet =
    ShareCardPaintSet(
        tagPaint =
            createTextPaint(
                color = palette.tagText,
                textSizePx = sp(resources, TAG_TEXT_SIZE_SP),
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD),
            ).apply { letterSpacing = TAG_LETTER_SPACING },
        titlePaint =
            createTextPaint(
                color = palette.secondaryText,
                textSizePx = sp(resources, TITLE_TEXT_SIZE_SP),
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL),
            ).apply { letterSpacing = TITLE_LETTER_SPACING },
        paragraphPaint =
            createTextPaint(
                color = palette.bodyText,
                textSizePx = sp(resources, bodyTextSizeSp),
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL),
            ).apply {
                letterSpacing =
                    if (shouldUseCenteredBody) {
                        CENTERED_BODY_LETTER_SPACING
                    } else {
                        DEFAULT_BODY_LETTER_SPACING
                    }
            },
        bulletPaint =
            createTextPaint(
                color = palette.bodyText,
                textSizePx = sp(resources, bodyTextSizeSp * BULLET_TEXT_SCALE),
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD),
            ).apply { letterSpacing = EMPHASIZED_LETTER_SPACING },
        quotePaint =
            createTextPaint(
                color = palette.secondaryText,
                textSizePx = sp(resources, bodyTextSizeSp * QUOTE_TEXT_SCALE),
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL),
            ).apply { letterSpacing = EMPHASIZED_LETTER_SPACING },
        codePaint =
            createTextPaint(
                color = palette.bodyText,
                textSizePx = sp(resources, bodyTextSizeSp * CODE_TEXT_SCALE),
                typeface = Typeface.MONOSPACE,
            ),
        footerPaint =
            createTextPaint(
                color = palette.secondaryText,
                textSizePx = sp(resources, FOOTER_TEXT_SIZE_SP),
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL),
            ).apply { letterSpacing = FOOTER_LETTER_SPACING },
    )

internal fun createTextPaint(
    color: Int,
    textSizePx: Float,
    typeface: Typeface,
): TextPaint =
    TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.textSize = textSizePx
        this.typeface = typeface
        isSubpixelText = true
    }

@SuppressLint("InlinedApi")
private fun defaultBreakStrategy(): Int = LineBreaker.BREAK_STRATEGY_HIGH_QUALITY

@SuppressLint("InlinedApi")
private fun defaultJustificationMode(): Int = LineBreaker.JUSTIFICATION_MODE_NONE

internal fun buildStaticLayout(
    text: String,
    paint: TextPaint,
    width: Int,
    maxLines: Int = Int.MAX_VALUE,
    alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
    paragraphLayoutPolicy: ShareCardParagraphLayoutPolicy? = null,
): StaticLayout {
    val layoutText = text.ifEmpty { BLANK_LAYOUT_TEXT }
    val resolvedAlignment = paragraphLayoutPolicy?.alignment ?: alignment

    return StaticLayout
        .Builder
        .obtain(
            layoutText,
            0,
            layoutText.length,
            paint,
            width.coerceAtLeast(MIN_RENDER_DIMENSION_PX),
        ).setAlignment(resolvedAlignment)
        .setIncludePad(false)
        .setBreakStrategy(paragraphLayoutPolicy?.breakStrategy ?: defaultBreakStrategy())
        .setHyphenationFrequency(
            paragraphLayoutPolicy?.hyphenationFrequency ?: Layout.HYPHENATION_FREQUENCY_NORMAL,
        ).setJustificationMode(
            paragraphLayoutPolicy?.justificationMode ?: defaultJustificationMode(),
        )
        .setLineSpacing(0f, LAYOUT_LINE_SPACING_MULTIPLIER)
        .setMaxLines(maxLines)
        .setEllipsize(TextUtils.TruncateAt.END)
        .build()
}

internal fun drawLayout(
    canvas: Canvas,
    layout: StaticLayout,
    x: Float,
    y: Float,
) {
    canvas.withTranslation(x, y) {
        layout.draw(this)
    }
}

internal fun dp(
    resources: Resources,
    value: Float,
): Float = value * resources.displayMetrics.density

internal fun sp(
    resources: Resources,
    value: Float,
): Float =
    TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )

internal fun formatShareCardTime(
    createdAtMillis: Long,
    formatter: DateTimeFormatter,
): String =
    formatter.format(
        Instant
            .ofEpochMilli(createdAtMillis)
            .atZone(ZoneId.systemDefault()),
    )
