package com.lomo.ui.component.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode

internal fun ASTNode.extractNodeText(content: String): String = content.substring(startOffset, endOffset)

internal fun ASTNode.extractModernImageOrNull(content: String): ModernMarkdownImage? {
    if (type != MarkdownElementTypes.IMAGE) return null
    val inlineLink = children.firstOrNull { it.type == MarkdownElementTypes.INLINE_LINK } ?: return null
    val destinationNode =
        inlineLink.findFirstDescendant(MarkdownElementTypes.LINK_DESTINATION) ?: return null
    val destination = destinationNode.extractNodeText(content).trim()
    if (destination.isEmpty()) return null

    val altText =
        inlineLink
            .children
            .firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
            ?.extractModernPlainText(content)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    return ModernMarkdownImage(
        destination = destination,
        title = altText,
    )
}

internal fun ASTNode.extractModernPlainText(content: String): String =
    when (type) {
        MarkdownTokenTypes.TEXT,
        MarkdownTokenTypes.WHITE_SPACE,
        MarkdownTokenTypes.EOL,
        MarkdownTokenTypes.HARD_LINE_BREAK,
        -> extractNodeText(content)

        else -> children.joinToString(separator = "") { it.extractModernPlainText(content) }
    }

internal fun buildModernMarkdownHeadingAnnotatedText(
    content: String,
    node: ASTNode,
    style: TextStyle,
    tokenSpec: ModernMarkdownTokenSpec,
): AnnotatedString {
    val fragment = node.extractModernHeadingMarkdownFragment(content)
    return if (fragment.isNotBlank()) {
        buildModernMarkdownAnnotatedTextFromFragment(
            fragment = fragment,
            style = style,
            tokenSpec = tokenSpec,
        )
    } else {
        buildModernMarkdownAnnotatedText(
            content = content,
            node = node,
            style = style,
            tokenSpec = tokenSpec,
        )
    }
}

internal fun ASTNode.extractModernHeadingMarkdownFragment(content: String): String {
    val tokenType =
        when (type) {
            MarkdownElementTypes.ATX_1,
            MarkdownElementTypes.ATX_2,
            MarkdownElementTypes.ATX_3,
            MarkdownElementTypes.ATX_4,
            MarkdownElementTypes.ATX_5,
            MarkdownElementTypes.ATX_6,
            -> MarkdownTokenTypes.ATX_CONTENT

            MarkdownElementTypes.SETEXT_1,
            MarkdownElementTypes.SETEXT_2,
            -> MarkdownTokenTypes.SETEXT_CONTENT

            else -> null
        }
    val directContent =
        tokenType
            ?.let { target -> children.firstOrNull { it.type == target } }
            ?.extractNodeText(content)
            ?.trim()
            .orEmpty()
    return directContent.ifBlank { extractModernPlainText(content).trim() }
}

internal fun ASTNode.findFirstDescendant(targetType: IElementType): ASTNode? {
    if (type == targetType) return this
    children.forEach { child ->
        val nested = child.findFirstDescendant(targetType)
        if (nested != null) return nested
    }
    return null
}

internal fun String.countLineIndexBeforeOffset(offset: Int): Int {
    var lineIndex = 0
    var index = 0
    val limit = offset.coerceIn(0, length)
    while (index < limit) {
        if (this[index] == '\n') {
            lineIndex++
        }
        index++
    }
    return lineIndex
}
