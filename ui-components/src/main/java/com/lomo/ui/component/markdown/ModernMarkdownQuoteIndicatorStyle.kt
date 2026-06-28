package com.lomo.ui.component.markdown

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode

internal data class ModernMarkdownQuoteIndicatorStyle(
    val thickness: Dp,
    val shape: Shape,
    val color: Color,
)

internal fun resolveModernMarkdownBlockTextStyle(
    node: ASTNode,
    tokenSpec: ModernMarkdownTokenSpec,
    baseParagraphStyle: TextStyle,
): TextStyle =
    when (node.type) {
        MarkdownElementTypes.BLOCK_QUOTE -> tokenSpec.quoteStyle
        else -> baseParagraphStyle
    }

internal fun resolveModernMarkdownQuoteIndicatorStyle(
    colorScheme: ColorScheme,
): ModernMarkdownQuoteIndicatorStyle =
    ModernMarkdownQuoteIndicatorStyle(
        thickness = MarkdownComponentTokens.BlockQuoteStartPadding,
        shape = MarkdownComponentTokens.CodeBlockShape,
        color = colorScheme.primary,
    )
