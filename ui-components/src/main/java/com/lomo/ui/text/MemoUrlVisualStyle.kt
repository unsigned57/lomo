package com.lomo.ui.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.style.TextDecoration

internal data class MemoUrlVisualStyle(
    val color: Color,
    val isUnderlineText: Boolean,
)

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
