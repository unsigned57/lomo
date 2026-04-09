package com.lomo.ui.component.markdown

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lomo.ui.theme.memoBodyTextStyle
import com.lomo.ui.theme.memoParagraphBlockSpacing
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import com.mikepenz.markdown.model.MarkdownPadding
import com.mikepenz.markdown.model.MarkdownTypography

internal data class ModernMarkdownTokenSpec(
    val paragraphStyle: TextStyle,
    val heading1Style: TextStyle,
    val heading2Style: TextStyle,
    val heading3Style: TextStyle,
    val heading4Style: TextStyle,
    val heading5Style: TextStyle,
    val heading6Style: TextStyle,
    val listStyle: TextStyle,
    val quoteStyle: TextStyle,
    val codeStyle: TextStyle,
    val inlineCodeStyle: TextStyle,
    val tableStyle: TextStyle,
    val linkStyle: TextLinkStyles,
    val highlightSpanStyle: SpanStyle,
    val blockSpacing: Dp,
    val listSpacing: Dp,
    val listItemSpacing: Dp,
)

internal fun createModernMarkdownTokenSpec(
    typography: Typography,
    linkColor: Color = Color.Unspecified,
    highlightBackgroundColor: Color = Color(0x66FFE082),
): ModernMarkdownTokenSpec {
    val paragraphStyle = typography.memoBodyTextStyle()
    val linkSpanStyle =
        SpanStyle(
            color = linkColor,
            textDecoration = TextDecoration.Underline,
        )
    val highlightSpanStyle =
        SpanStyle(
            background = highlightBackgroundColor,
        )

    return ModernMarkdownTokenSpec(
        paragraphStyle = paragraphStyle,
        heading1Style = typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        heading2Style = typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        heading3Style = typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        heading4Style = typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        heading5Style = typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
        heading6Style = typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
        listStyle = paragraphStyle,
        quoteStyle = typography.bodyMedium,
        codeStyle = typography.bodySmall,
        inlineCodeStyle = typography.bodySmall,
        tableStyle = paragraphStyle,
        linkStyle = TextLinkStyles(style = linkSpanStyle),
        highlightSpanStyle = highlightSpanStyle,
        blockSpacing = memoParagraphBlockSpacing(),
        listSpacing = memoParagraphBlockSpacing(),
        listItemSpacing = 4.dp,
    )
}

internal fun resolveVisibleModernMarkdownTokenSpec(
    baseSpec: ModernMarkdownTokenSpec,
    primaryTextColor: Color,
    secondaryTextColor: Color,
): ModernMarkdownTokenSpec =
    baseSpec.copy(
        paragraphStyle = baseSpec.paragraphStyle.withVisibleMarkdownTextColor(primaryTextColor),
        heading1Style = baseSpec.heading1Style.withVisibleMarkdownTextColor(primaryTextColor),
        heading2Style = baseSpec.heading2Style.withVisibleMarkdownTextColor(primaryTextColor),
        heading3Style = baseSpec.heading3Style.withVisibleMarkdownTextColor(primaryTextColor),
        heading4Style = baseSpec.heading4Style.withVisibleMarkdownTextColor(primaryTextColor),
        heading5Style = baseSpec.heading5Style.withVisibleMarkdownTextColor(primaryTextColor),
        heading6Style = baseSpec.heading6Style.withVisibleMarkdownTextColor(secondaryTextColor),
        listStyle = baseSpec.listStyle.withVisibleMarkdownTextColor(primaryTextColor),
        quoteStyle = baseSpec.quoteStyle.withVisibleMarkdownTextColor(primaryTextColor),
        codeStyle = baseSpec.codeStyle.withVisibleMarkdownTextColor(primaryTextColor),
        inlineCodeStyle = baseSpec.inlineCodeStyle.withVisibleMarkdownTextColor(primaryTextColor),
        tableStyle = baseSpec.tableStyle.withVisibleMarkdownTextColor(primaryTextColor),
        linkStyle = baseSpec.linkStyle.withVisibleMarkdownLinkColor(primaryTextColor),
        highlightSpanStyle = baseSpec.highlightSpanStyle,
    )

internal data class ModernMarkdownTokens(
    val typography: MarkdownTypography,
    val padding: MarkdownPadding,
)

@Composable
internal fun rememberModernMarkdownTokenSpec(): ModernMarkdownTokenSpec {
    val materialTypography = MaterialTheme.typography
    val colorScheme = MaterialTheme.colorScheme
    return remember(materialTypography, colorScheme) {
        resolveVisibleModernMarkdownTokenSpec(
            baseSpec =
                createModernMarkdownTokenSpec(
                    typography = materialTypography,
                    linkColor = colorScheme.primary,
                    highlightBackgroundColor = colorScheme.secondaryContainer.copy(alpha = 0.55f),
                ),
            primaryTextColor = colorScheme.onSurface,
            secondaryTextColor = colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun rememberModernMarkdownTokens(): ModernMarkdownTokens {
    val materialTypography = MaterialTheme.typography
    val colorScheme = MaterialTheme.colorScheme
    return remember(materialTypography, colorScheme) {
        val spec =
            resolveVisibleModernMarkdownTokenSpec(
                baseSpec =
                    createModernMarkdownTokenSpec(
                        typography = materialTypography,
                        linkColor = colorScheme.primary,
                        highlightBackgroundColor = colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    ),
                primaryTextColor = colorScheme.onSurface,
                secondaryTextColor = colorScheme.onSurfaceVariant,
            )

        ModernMarkdownTokens(
            typography =
                DefaultMarkdownTypography(
                    spec.heading1Style,
                    spec.heading2Style,
                    spec.heading3Style,
                    spec.heading4Style,
                    spec.heading5Style,
                    spec.heading6Style,
                    spec.paragraphStyle,
                    spec.codeStyle,
                    spec.inlineCodeStyle,
                    spec.quoteStyle,
                    spec.paragraphStyle,
                    spec.listStyle,
                    spec.listStyle,
                    spec.listStyle,
                    spec.linkStyle,
                    spec.tableStyle,
                ),
            padding =
                LomoMarkdownPadding(
                    block = spec.blockSpacing,
                    list = spec.listSpacing,
                    listItemTop = spec.listItemSpacing,
                    listItemBottom = spec.listItemSpacing,
            ),
        )
    }
}

private fun TextStyle.withVisibleMarkdownTextColor(
    fallbackColor: Color,
): TextStyle = if (color == Color.Unspecified) copy(color = fallbackColor) else this

private fun TextLinkStyles.withVisibleMarkdownLinkColor(
    fallbackColor: Color,
): TextLinkStyles =
    TextLinkStyles(
        style = (style ?: SpanStyle()).withVisibleMarkdownSpanColor(fallbackColor),
        focusedStyle = focusedStyle?.withVisibleMarkdownSpanColor(fallbackColor),
        hoveredStyle = hoveredStyle?.withVisibleMarkdownSpanColor(fallbackColor),
        pressedStyle = pressedStyle?.withVisibleMarkdownSpanColor(fallbackColor),
    )

private fun SpanStyle.withVisibleMarkdownSpanColor(
    fallbackColor: Color,
): SpanStyle = if (color == Color.Unspecified) copy(color = fallbackColor) else this

private data class LomoMarkdownPadding(
    override val block: Dp,
    override val list: Dp,
    override val listItemTop: Dp,
    override val listItemBottom: Dp,
    override val listIndent: Dp = 8.dp,
    override val codeBlock: PaddingValues = PaddingValues(8.dp),
    override val blockQuote: PaddingValues = PaddingValues(vertical = 4.dp),
    override val blockQuoteText: PaddingValues = PaddingValues(start = 8.dp),
    override val blockQuoteBar: PaddingValues.Absolute = PaddingValues.Absolute(0.dp, 0.dp, 0.dp, 0.dp),
) : MarkdownPadding
