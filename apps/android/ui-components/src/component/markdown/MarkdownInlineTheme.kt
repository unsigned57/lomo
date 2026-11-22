package com.lomo.ui.component.markdown

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em

private val INLINE_CODE_FONT_SIZE = 0.9f.em

@Composable
internal fun rememberThemedMarkdownText(
    text: AnnotatedString,
    inlineCodeFontSize: TextUnit = INLINE_CODE_FONT_SIZE,
    styleLinks: Boolean = false,
): AnnotatedString {
    val colorScheme = MaterialTheme.colorScheme
    return remember(text, colorScheme, inlineCodeFontSize, styleLinks) {
        applyMarkdownInlineTheme(
            text = text,
            colorScheme = colorScheme,
            inlineCodeFontSize = inlineCodeFontSize,
            styleLinks = styleLinks,
        )
    }
}

internal fun applyMarkdownInlineTheme(
    text: AnnotatedString,
    colorScheme: ColorScheme,
    inlineCodeFontSize: TextUnit = INLINE_CODE_FONT_SIZE,
    styleLinks: Boolean = false,
): AnnotatedString {
    if (text.isEmpty()) return text
    val annotations = text.getStringAnnotations(MARKDOWN_INLINE_ROLE_TAG, 0, text.length)
    val links = if (styleLinks) text.getLinkAnnotations(0, text.length) else emptyList()
    if (annotations.isEmpty() && links.isEmpty()) return text

    return AnnotatedString.Builder(text).apply {
        for (annotation in annotations) {
            val style =
                when (annotation.item) {
                    INLINE_ROLE_CODE ->
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = inlineCodeFontSize,
                            color = colorScheme.onSurface,
                            background = colorScheme.surfaceContainerHighest,
                        )

                    INLINE_ROLE_HIGHLIGHT ->
                        SpanStyle(
                            fontWeight = FontWeight.SemiBold,
                            color = colorScheme.onTertiaryContainer,
                            background = colorScheme.tertiaryContainer,
                        )

                    INLINE_ROLE_WIKI ->
                        SpanStyle(
                            fontWeight = FontWeight.Medium,
                            color = colorScheme.primary,
                            background = MarkdownRenderTokens.wikiPillBackgroundColor(colorScheme),
                        )

                    else -> null
                }
            if (style != null) {
                addStyle(style, annotation.start, annotation.end)
            }
        }
        for (link in links) {
            addStyle(
                SpanStyle(
                    color = colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                ),
                link.start,
                link.end,
            )
        }
    }.toAnnotatedString()
}
