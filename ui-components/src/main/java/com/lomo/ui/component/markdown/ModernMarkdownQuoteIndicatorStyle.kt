package com.lomo.ui.component.markdown

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode

internal data class ModernMarkdownQuoteIndicatorStyle(
    val thickness: Dp,
    val cornerRadius: Dp,
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
        thickness = 4.dp,
        cornerRadius = 2.dp,
        color = colorScheme.primary,
    )
