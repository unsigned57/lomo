package com.lomo.ui.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle

internal fun AnnotatedString.withSearchHighlight(
    query: String,
    highlightColor: Color,
): AnnotatedString {
    if (query.isBlank() || isEmpty()) return this

    val editable = AnnotatedString.Builder()
    editable.append(this)
    val lowerText = text.lowercase()
    val lowerQuery = query.lowercase()
    var startIndex = 0
    while (startIndex < lowerText.length) {
        val foundIndex = lowerText.indexOf(lowerQuery, startIndex)
        if (foundIndex < 0) break
        editable.addStyle(
            SpanStyle(background = highlightColor),
            foundIndex,
            foundIndex + lowerQuery.length,
        )
        startIndex = foundIndex + lowerQuery.length
    }
    return editable.toAnnotatedString()
}

internal fun AnnotatedString.resolveSpanStyle(offset: Int): SpanStyle =
    spanStyles
        .lastOrNull { range -> offset in range.start until range.end }
        ?.item
        ?: SpanStyle()

internal fun AnnotatedString.resolveBackgroundColor(offset: Int): Color =
    spanStyles
        .lastOrNull { range ->
            offset in range.start until range.end && range.item.background != Color.Unspecified
        }
        ?.item
        ?.background
        ?: Color.Unspecified

internal fun AnnotatedString.toMemoTextLinkRanges(): List<MemoTextLinkRange> =
    getLinkAnnotations(0, length)
        .mapNotNull { range ->
            val url = (range.item as? LinkAnnotation.Url)?.url ?: return@mapNotNull null
            MemoTextLinkRange(
                start = range.start,
                end = range.end,
                url = url,
            )
        }

internal fun AnnotatedString.resolveLinkVisualStyle(
    offset: Int,
    defaultLinkColor: Color,
): MemoUrlVisualStyle? =
    getLinkAnnotations(offset, (offset + 1).coerceAtMost(length))
        .lastOrNull()
        ?.let { range ->
            resolveMemoUrlVisualStyle(
                text = this,
                start = range.start,
                end = range.end,
                defaultColor = defaultLinkColor,
                defaultUnderline = false,
            )
        }
