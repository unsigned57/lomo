package com.lomo.ui.text

import android.graphics.Typeface
import android.os.Build
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.TextViewCompat

private const val BOLD_WEIGHT_THRESHOLD = 600

internal fun CharSequence.shouldUsePlatformMemoParagraphRendering(): Boolean = isNotEmpty()

internal fun AnnotatedString.shouldUsePlatformMemoParagraphRendering(): Boolean =
    isNotEmpty() &&
        spanStyles.isEmpty() &&
        paragraphStyles.isEmpty() &&
        !hasLinkAnnotations(0, length) &&
        getStringAnnotations(0, length).isEmpty()

internal fun CharSequence.shouldUseTextViewMemoParagraphRendering(): Boolean = isNotEmpty()

internal fun AnnotatedString.shouldUseTextViewMemoParagraphRendering(): Boolean = isNotEmpty()

@Composable
internal fun MemoParagraphText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    selectable: Boolean = false,
) {
    PlatformMemoParagraphText(
        text = text,
        style = style,
        modifier = modifier,
        maxLines = maxLines,
        overflow = overflow,
        selectable = selectable,
    )
}

@Composable
internal fun MemoParagraphText(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    selectable: Boolean = false,
) {
    PlatformMemoParagraphText(
        text = text.toPlatformParagraphCharSequence(),
        style = style,
        modifier = modifier,
        maxLines = maxLines,
        overflow = overflow,
        selectable = selectable,
    )
}

@Composable
private fun PlatformMemoParagraphText(
    text: CharSequence,
    style: TextStyle,
    modifier: Modifier,
    maxLines: Int,
    overflow: TextOverflow,
    selectable: Boolean,
) {
    val density = LocalDensity.current

    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                includeFontPadding = false
                setPadding(0, 0, 0, 0)
            }
        },
        update = { textView ->
            textView.applyMemoParagraphTextStyle(
                text = text,
                style = style,
                density = density,
                maxLines = maxLines,
                overflow = overflow,
                selectable = selectable,
            )
        },
    )
}

internal fun TextView.applyMemoParagraphTextStyle(
    text: CharSequence,
    style: TextStyle,
    density: Density,
    maxLines: Int,
    overflow: TextOverflow,
    selectable: Boolean,
) {
    this.text = text
    applyMemoParagraphAppearance(text, style, density, maxLines, overflow, selectable)
}

internal fun TextStyle.resolvePlatformTypeface(): Typeface {
    val baseTypeface = if (fontFamily == FontFamily.Monospace) Typeface.MONOSPACE else Typeface.SANS_SERIF
    val italic = fontStyle == FontStyle.Italic
    val weight = fontWeight?.weight ?: Typeface.NORMAL

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
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

internal fun TextOverflow.toEllipsize(): TextUtils.TruncateAt? =
    when (this) {
        TextOverflow.Ellipsis -> TextUtils.TruncateAt.END
        else -> null
    }
