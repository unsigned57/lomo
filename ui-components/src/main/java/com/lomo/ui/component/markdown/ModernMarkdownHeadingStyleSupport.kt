package com.lomo.ui.component.markdown

import androidx.compose.ui.text.TextStyle
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode

internal fun resolveModernMarkdownHeadingStyle(
    node: ASTNode,
    tokenSpec: ModernMarkdownTokenSpec,
): TextStyle? =
    when (node.type) {
        MarkdownElementTypes.ATX_1,
        MarkdownElementTypes.SETEXT_1,
        -> tokenSpec.heading1Style

        MarkdownElementTypes.ATX_2,
        MarkdownElementTypes.SETEXT_2,
        -> tokenSpec.heading2Style

        MarkdownElementTypes.ATX_3 -> tokenSpec.heading3Style
        MarkdownElementTypes.ATX_4 -> tokenSpec.heading4Style
        MarkdownElementTypes.ATX_5 -> tokenSpec.heading5Style
        MarkdownElementTypes.ATX_6 -> tokenSpec.heading6Style
        else -> null
    }
