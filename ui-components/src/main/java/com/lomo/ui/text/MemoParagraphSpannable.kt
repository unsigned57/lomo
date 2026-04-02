package com.lomo.ui.text

import android.content.ActivityNotFoundException
import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
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
    this is Spanned && getSpans(0, length, URLSpan::class.java).isNotEmpty()

internal fun AnnotatedString.toPlatformParagraphCharSequence(): CharSequence {
    if (isEmpty()) return text

    val spannable = SpannableString(text)

    spanStyles.forEach { range ->
        spannable.applyComposeSpanStyle(range.item, range.start, range.end)
    }

    getLinkAnnotations(0, length).forEach { range ->
        val link = range.item as? LinkAnnotation.Url ?: return@forEach
        spannable.setSpan(
            MemoUrlSpan(link.url),
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

private class MemoUrlSpan(
    private val url: String,
) : ClickableSpan() {
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
