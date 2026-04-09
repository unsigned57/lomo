package com.lomo.ui.text

import android.content.ActivityNotFoundException
import android.content.Intent
import android.text.TextPaint
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.view.View
import androidx.core.net.toUri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration

internal fun CharSequence.hasPlatformLinks(): Boolean =
    this is Spanned && getSpans(0, length, ClickableSpan::class.java).isNotEmpty()

internal data class MemoUrlVisualStyle(
    val color: Color,
    val isUnderlineText: Boolean,
)

internal fun AnnotatedString.toPlatformParagraphCharSequence(
    defaultLinkColor: Color,
    defaultUnderline: Boolean = false,
): CharSequence {
    if (isEmpty()) return text

    val spannable = SpannableString(text)

    spanStyles.forEach { range ->
        spannable.applyComposeSpanStyle(range.item, range.start, range.end)
    }

    getLinkAnnotations(0, length).forEach { range ->
        val link = range.item as? LinkAnnotation.Url ?: return@forEach
        spannable.setSpan(
            MemoUrlSpan(
                url = link.url,
                visualStyle =
                    resolveMemoUrlVisualStyle(
                        text = this,
                        start = range.start,
                        end = range.end,
                        defaultColor = defaultLinkColor,
                        defaultUnderline = defaultUnderline,
                    ),
            ),
            range.start,
            range.end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    return spannable
}

private fun SpannableString.applyComposeSpanStyle(
    spanStyle: SpanStyle,
    start: Int,
    end: Int,
) {
    if (spanStyle.color != Color.Unspecified) {
        setSpan(
            ForegroundColorSpan(spanStyle.color.toArgb()),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
    if (spanStyle.background != Color.Unspecified) {
        setSpan(
            BackgroundColorSpan(spanStyle.background.toArgb()),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    val fontWeight = spanStyle.fontWeight?.weight ?: android.graphics.Typeface.NORMAL
    val italic = spanStyle.fontStyle == FontStyle.Italic
    if (spanStyle.fontWeight != null || italic) {
        setSpan(
            StyleSpan(resolveMemoTypefaceStyle(fontWeight, italic)),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
    if (spanStyle.fontFamily == FontFamily.Monospace) {
        setSpan(
            TypefaceSpan("monospace"),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
    if (spanStyle.textDecoration?.contains(TextDecoration.Underline) == true) {
        setSpan(
            UnderlineSpan(),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
    if (spanStyle.textDecoration?.contains(TextDecoration.LineThrough) == true) {
        setSpan(
            StrikethroughSpan(),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
}

internal fun resolveMemoUrlVisualStyle(
    text: AnnotatedString,
    start: Int,
    end: Int,
    defaultColor: Color,
    defaultUnderline: Boolean,
): MemoUrlVisualStyle {
    val linkStyle =
        text
            .getLinkAnnotations(start, end)
            .lastOrNull { range -> range.start < end && range.end > start }
            ?.item
            ?.let { annotation -> annotation as? LinkAnnotation.Url }
            ?.styles
            ?.style
    val coveringStyles =
        text.spanStyles.filter { range ->
            range.start < end && range.end > start
        }

    val resolvedColor =
        when {
            linkStyle?.color != null && linkStyle.color != Color.Unspecified -> linkStyle.color
            else ->
                coveringStyles
                    .lastOrNull { it.item.color != Color.Unspecified }
                    ?.item
                    ?.color
                    ?: defaultColor
        }
    val underlineDecoration =
        coveringStyles
            .mapNotNull { range -> range.item.textDecoration }
            .lastOrNull()
    val resolvedUnderline =
        when {
            linkStyle != null -> linkStyle.textDecoration?.contains(TextDecoration.Underline) == true
            underlineDecoration != null -> underlineDecoration.contains(TextDecoration.Underline)
            else -> defaultUnderline
        }

    return MemoUrlVisualStyle(
        color = resolvedColor,
        isUnderlineText = resolvedUnderline,
    )
}

private class MemoUrlSpan(
    private val url: String,
    private val visualStyle: MemoUrlVisualStyle,
) : ClickableSpan() {
    override fun updateDrawState(ds: TextPaint) {
        ds.color = visualStyle.color.toArgb()
        ds.isUnderlineText = visualStyle.isUnderlineText
    }

    override fun onClick(widget: View) {
        val intent =
            Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        try {
            widget.context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Keep rendering stable when the device has no URL handler.
        }
    }
}
