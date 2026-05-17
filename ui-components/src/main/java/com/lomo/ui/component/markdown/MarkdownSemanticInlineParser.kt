package com.lomo.ui.component.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

private const val SURROUNDED_TEXT_MIN_LENGTH = 2
private const val EDGE_OFFSET = 1

internal fun parseSemanticInlines(
    nodes: List<ASTNode>,
    content: String,
    references: Map<String, MarkdownLinkReference>,
): List<MarkdownSemanticInline> =
    nodes.flatMap { node -> parseSemanticInline(node, content, references) }

private fun parseSemanticInline(
    node: ASTNode,
    content: String,
    references: Map<String, MarkdownLinkReference>,
): List<MarkdownSemanticInline> =
    when (node.type) {
        MarkdownTokenTypes.TEXT,
        MarkdownTokenTypes.WHITE_SPACE,
        -> listOf(MarkdownSemanticInline.Text(node.extractNodeText(content)))

        MarkdownTokenTypes.EOL -> listOf(MarkdownSemanticInline.SoftBreak)
        MarkdownTokenTypes.HARD_LINE_BREAK -> listOf(MarkdownSemanticInline.HardBreak)
        MarkdownTokenTypes.HTML_TAG -> listOf(MarkdownSemanticInline.HtmlInline(node.extractNodeText(content)))
        MarkdownElementTypes.EMPH ->
            listOf(
                MarkdownSemanticInline.Emphasis(
                    inlines = parseSemanticInlines(node.children, content, references),
                ),
            )

        MarkdownElementTypes.STRONG ->
            listOf(
                MarkdownSemanticInline.Strong(
                    inlines = parseSemanticInlines(node.children, content, references),
                ),
            )

        GFMElementTypes.STRIKETHROUGH ->
            listOf(
                MarkdownSemanticInline.Strikethrough(
                    inlines = parseSemanticInlines(node.children, content, references),
                ),
            )

        MarkdownElementTypes.CODE_SPAN ->
            listOf(
                MarkdownSemanticInline.Code(
                    plainText =
                        node.children
                            .filterNot(::isCodeSpanFence)
                            .joinToString(separator = "") { child -> child.extractNodeText(content) },
                ),
            )

        MarkdownElementTypes.INLINE_LINK,
        MarkdownElementTypes.FULL_REFERENCE_LINK,
        MarkdownElementTypes.SHORT_REFERENCE_LINK,
        -> listOf(node.parseSemanticLink(content, references))

        MarkdownElementTypes.IMAGE -> listOf(node.parseSemanticImage(content, references))
        MarkdownElementTypes.AUTOLINK -> listOf(node.parseSemanticAutolink(content))
        MarkdownElementTypes.LINK_TEXT,
        MarkdownElementTypes.LINK_LABEL,
        -> parseSemanticInlines(node.children, content, references)

        MarkdownElementTypes.LINK_DESTINATION,
        MarkdownElementTypes.LINK_TITLE,
        MarkdownTokenTypes.EMPH,
        MarkdownTokenTypes.LBRACKET,
        MarkdownTokenTypes.RBRACKET,
        MarkdownTokenTypes.LPAREN,
        MarkdownTokenTypes.RPAREN,
        MarkdownTokenTypes.EXCLAMATION_MARK,
        MarkdownTokenTypes.LT,
        MarkdownTokenTypes.GT,
        MarkdownTokenTypes.SINGLE_QUOTE,
        MarkdownTokenTypes.DOUBLE_QUOTE,
        GFMTokenTypes.TILDE,
        -> emptyList()

        else -> parseSemanticInlines(node.children, content, references)
    }

private fun ASTNode.parseSemanticLink(
    content: String,
    references: Map<String, MarkdownLinkReference>,
): MarkdownSemanticInline.Link {
    val linkTextNode = children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
    val inlines = parseSemanticInlines(linkTextNode?.children.orEmpty(), content, references)
    val visibleInlines =
        inlines.ifEmpty {
            listOf(MarkdownSemanticInline.Text(extractModernPlainText(content)))
        }
    val reference =
        when (type) {
            MarkdownElementTypes.INLINE_LINK ->
                MarkdownLinkReference(
                    destination =
                        findFirstDescendant(MarkdownElementTypes.LINK_DESTINATION)
                            ?.extractNodeText(content)
                            ?.trim()
                            .orEmpty(),
                    title =
                        findFirstDescendant(MarkdownElementTypes.LINK_TITLE)
                            ?.extractLinkTitleText(content)
                            ?.takeIf(String::isNotEmpty),
                )

            MarkdownElementTypes.FULL_REFERENCE_LINK,
            MarkdownElementTypes.SHORT_REFERENCE_LINK,
            -> {
                val label =
                    children
                        .firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
                        ?.extractLinkLabelText(content)
                        ?.takeIf(String::isNotEmpty)
                        ?: linkTextNode?.extractLinkLabelText(content)
                references[normalizeReferenceLabel(label.orEmpty())]
                    ?: MarkdownLinkReference(destination = "", title = null)
            }

            else -> MarkdownLinkReference(destination = "", title = null)
        }
    return MarkdownSemanticInline.Link(
        destination = reference.destination,
        title = reference.title,
        inlines = visibleInlines,
    )
}

private fun ASTNode.parseSemanticImage(
    content: String,
    references: Map<String, MarkdownLinkReference>,
): MarkdownSemanticInline.Image {
    val linkNode =
        children.firstOrNull { child ->
            child.type == MarkdownElementTypes.INLINE_LINK ||
                child.type == MarkdownElementTypes.FULL_REFERENCE_LINK ||
                child.type == MarkdownElementTypes.SHORT_REFERENCE_LINK
        }
    val linkTextNode = linkNode?.children?.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
    val altText =
        parseSemanticInlines(linkTextNode?.children.orEmpty(), content, references)
            .plainText()
            .trim()
    val resolved =
        when (linkNode?.type) {
            MarkdownElementTypes.INLINE_LINK ->
                MarkdownLinkReference(
                    destination =
                        linkNode
                            .findFirstDescendant(MarkdownElementTypes.LINK_DESTINATION)
                            ?.extractNodeText(content)
                            ?.trim()
                            .orEmpty(),
                    title =
                        linkNode
                            .findFirstDescendant(MarkdownElementTypes.LINK_TITLE)
                            ?.extractLinkTitleText(content)
                            ?.takeIf(String::isNotEmpty),
                )

            MarkdownElementTypes.FULL_REFERENCE_LINK,
            MarkdownElementTypes.SHORT_REFERENCE_LINK,
            -> {
                val label =
                    linkNode
                        .children
                        .firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
                        ?.extractLinkLabelText(content)
                        ?.takeIf(String::isNotEmpty)
                        ?: altText
                references[normalizeReferenceLabel(label)]
                    ?: MarkdownLinkReference(destination = "", title = null)
            }

            else -> MarkdownLinkReference(destination = "", title = null)
        }
    return MarkdownSemanticInline.Image(
        destination = resolved.destination,
        title = resolved.title,
        altText = altText,
    )
}

private fun ASTNode.parseSemanticAutolink(content: String): MarkdownSemanticInline.Link {
    val destination =
        children
            .firstOrNull { it.type == MarkdownTokenTypes.AUTOLINK || it.type == GFMTokenTypes.GFM_AUTOLINK }
            ?.extractNodeText(content)
            ?.trim()
            .orEmpty()
    return MarkdownSemanticInline.Link(
        destination = destination,
        title = null,
        inlines = listOf(MarkdownSemanticInline.Text(destination)),
    )
}

internal fun parseInlineMarkdownFragment(
    fragment: String,
    references: Map<String, MarkdownLinkReference>,
): List<MarkdownSemanticInline> {
    val root = parseModernMarkdownDocument(fragment)
    val renderNode = root.children.firstOrNull { it.type != MarkdownTokenTypes.EOL } ?: root
    return parseSemanticInlines(renderNode.children, fragment, references)
}

internal fun ASTNode.extractLinkLabelText(content: String): String =
    if (children.isEmpty()) {
        extractNodeText(content).trim().removeSurrounding("[", "]")
    } else {
        extractModernPlainText(content).trim()
    }

internal fun ASTNode.extractLinkTitleText(content: String): String {
    val raw =
        if (children.isEmpty()) {
            extractNodeText(content)
        } else {
            extractModernPlainText(content)
        }.trim()
    return when {
        raw.length >= SURROUNDED_TEXT_MIN_LENGTH && raw.startsWith('"') && raw.endsWith('"') ->
            raw.substring(EDGE_OFFSET, raw.length - EDGE_OFFSET)
        raw.length >= SURROUNDED_TEXT_MIN_LENGTH && raw.startsWith('\'') && raw.endsWith('\'') ->
            raw.substring(EDGE_OFFSET, raw.length - EDGE_OFFSET)
        raw.length >= SURROUNDED_TEXT_MIN_LENGTH && raw.startsWith('(') && raw.endsWith(')') ->
            raw.substring(EDGE_OFFSET, raw.length - EDGE_OFFSET)
        else -> raw
    }
}

internal fun trimEdgeWhitespace(inlines: List<MarkdownSemanticInline>): List<MarkdownSemanticInline> {
    if (inlines.isEmpty()) return inlines
    val trimmed = inlines.toMutableList()
    trimmed.trimEdgeText(fromStart = true)
    trimmed.trimEdgeText(fromStart = false)
    return trimmed
}

private fun MutableList<MarkdownSemanticInline>.trimEdgeText(fromStart: Boolean) {
    while (isNotEmpty()) {
        val index = if (fromStart) 0 else lastIndex
        val inline = this[index]
        val text = (inline as? MarkdownSemanticInline.Text)?.plainText ?: return
        val updated = if (fromStart) text.trimStart() else text.trimEnd()
        when {
            updated == text -> return
            updated.isEmpty() -> removeAt(index)
            else -> {
                this[index] = MarkdownSemanticInline.Text(updated)
                return
            }
        }
    }
}

private fun isCodeSpanFence(node: ASTNode): Boolean =
    node.type == MarkdownTokenTypes.BACKTICK || node.type == MarkdownTokenTypes.ESCAPED_BACKTICKS
