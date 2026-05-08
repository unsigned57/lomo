package com.lomo.ui.text

import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp
import kotlin.math.ceil

private const val BOLD_WEIGHT_THRESHOLD = 600
private const val DEFAULT_MEMO_TEXT_SIZE_SP = 14

internal data class MemoLineMetrics(
    val lineHeightPx: Float,
    val baselinePx: Float,
)

internal class AndroidMemoTextMeasurer(
    private val paint: TextPaint,
) : MemoTextMeasurer {
    override fun measureText(
        text: String,
        start: Int,
        end: Int,
    ): Float = paint.measureText(text, start, end)
}

internal fun TextStyle.toBaseTextPaint(density: Density): TextPaint =
    TextPaint(Paint.ANTI_ALIAS_FLAG).also { paint ->
        paint.color = color.toArgb()
        paint.textSize = resolveFontSizePx(density)
        paint.typeface = resolvePlatformTypeface()
        paint.letterSpacing = 0f
    }

internal fun TextStyle.toGlyphTextPaint(
    annotatedText: AnnotatedString,
    offset: Int,
    defaultLinkColor: Color,
    density: Density,
): TextPaint {
    val spanStyle = annotatedText.resolveSpanStyle(offset)
    val linkStyle = annotatedText.resolveLinkVisualStyle(offset, defaultLinkColor)
    return TextPaint(Paint.ANTI_ALIAS_FLAG).also { paint ->
        paint.color = resolveGlyphColor(spanStyle, linkStyle).toArgb()
        paint.textSize = resolveFontSizePx(density)
        paint.typeface = resolveTypeface(spanStyle)
        paint.isUnderlineText =
            linkStyle?.isUnderlineText == true ||
                spanStyle.textDecoration?.contains(TextDecoration.Underline) == true
        paint.isStrikeThruText = spanStyle.textDecoration?.contains(TextDecoration.LineThrough) == true
    }
}

internal fun TextStyle.resolveLineMetrics(
    density: Density,
    paint: TextPaint,
): MemoLineMetrics {
    val fontMetrics = paint.fontMetrics
    val naturalHeight = fontMetrics.descent - fontMetrics.ascent
    val lineHeight =
        with(density) {
            if (lineHeight == TextUnit.Unspecified) {
                naturalHeight
            } else {
                lineHeight.toPx().coerceAtLeast(naturalHeight)
            }
        }
    val baseline = -fontMetrics.ascent + (lineHeight - naturalHeight) / 2f
    return MemoLineMetrics(
        lineHeightPx = ceil(lineHeight),
        baselinePx = baseline,
    )
}

internal fun TextStyle.resolveMemoLetterSpacingPx(density: Density): Float =
    with(density) {
        when {
            letterSpacing == TextUnit.Unspecified -> 0f
            letterSpacing.type == TextUnitType.Sp -> letterSpacing.toPx()
            letterSpacing.type == TextUnitType.Em -> resolveFontSizePx(this) * letterSpacing.value
            else -> 0f
        }
    }

internal fun TextStyle.resolvePlatformTypeface(): Typeface {
    val baseTypeface = if (fontFamily == FontFamily.Monospace) Typeface.MONOSPACE else Typeface.SANS_SERIF
    val italic = fontStyle == FontStyle.Italic
    val weight = fontWeight?.weight ?: Typeface.NORMAL

    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        Typeface.create(baseTypeface, weight, italic)
    } else {
        Typeface.create(baseTypeface, resolveMemoTypefaceStyle(weight, italic))
    }
}

internal fun resolveMemoTypefaceStyle(
    weight: Int,
    italic: Boolean,
): Int =
    when {
        weight >= BOLD_WEIGHT_THRESHOLD && italic -> Typeface.BOLD_ITALIC
        weight >= BOLD_WEIGHT_THRESHOLD -> Typeface.BOLD
        italic -> Typeface.ITALIC
        else -> Typeface.NORMAL
    }

private fun TextStyle.resolveFontSizePx(density: Density): Float =
    with(density) {
        if (fontSize == TextUnit.Unspecified) {
            DEFAULT_MEMO_TEXT_SIZE_SP.sp.toPx()
        } else {
            fontSize.toPx()
        }
    }

private fun TextStyle.resolveTypeface(spanStyle: SpanStyle): Typeface {
    val resolvedFontFamily = spanStyle.fontFamily ?: fontFamily
    val resolvedFontStyle = spanStyle.fontStyle ?: fontStyle
    val resolvedFontWeight = spanStyle.fontWeight ?: fontWeight
    val baseTypeface = if (resolvedFontFamily == FontFamily.Monospace) Typeface.MONOSPACE else Typeface.SANS_SERIF
    val italic = resolvedFontStyle == FontStyle.Italic
    val weight = resolvedFontWeight?.weight ?: Typeface.NORMAL
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        Typeface.create(baseTypeface, weight, italic)
    } else {
        Typeface.create(baseTypeface, resolveMemoTypefaceStyle(weight, italic))
    }
}

private fun TextStyle.resolveGlyphColor(
    spanStyle: SpanStyle,
    linkStyle: MemoUrlVisualStyle?,
): Color =
    when {
        linkStyle != null -> linkStyle.color
        spanStyle.color != Color.Unspecified -> spanStyle.color
        else -> color
    }
