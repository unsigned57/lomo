package com.lomo.ui.component.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

private const val H1_LEVEL = 1
private const val H2_LEVEL = 2
private const val H3_LEVEL = 3
private const val H4_LEVEL = 4
private const val H5_LEVEL = 5
private const val H6_LEVEL = 6

internal fun parseMarkdownLinkReferences(
    root: ASTNode,
    content: String,
): Map<String, MarkdownLinkReference> =
    root.children
        .filter { it.type == MarkdownElementTypes.LINK_DEFINITION }
        .mapNotNull { node ->
            val label =
                node.children
                    .firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
                    ?.extractLinkLabelText(content)
                    ?.takeIf(String::isNotEmpty)
                    ?: return@mapNotNull null
            val destination =
                node.children
                    .firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
                    ?.extractNodeText(content)
                    ?.trim()
                    ?: return@mapNotNull null
            val title =
                node.children
                    .firstOrNull { it.type == MarkdownElementTypes.LINK_TITLE }
                    ?.extractLinkTitleText(content)
                    ?.takeIf(String::isNotEmpty)
            normalizeReferenceLabel(label) to MarkdownLinkReference(destination = destination, title = title)
        }.toMap()

internal fun parseMarkdownSemanticBlocks(
    nodes: List<ASTNode>,
    content: String,
    references: Map<String, MarkdownLinkReference>,
): List<MarkdownSemanticBlock> =
    nodes.flatMap { node -> parseSemanticBlock(node, content, references) }

internal fun parseSemanticBlock(
    node: ASTNode,
    content: String,
    references: Map<String, MarkdownLinkReference>,
): List<MarkdownSemanticBlock> =
    when (node.type) {
        MarkdownElementTypes.PARAGRAPH ->
            listOf(
                MarkdownSemanticBlock.Paragraph(
                    inlines = parseSemanticInlines(node.children, content, references),
                ),
            )

        MarkdownElementTypes.ATX_1,
        MarkdownElementTypes.ATX_2,
        MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4,
        MarkdownElementTypes.ATX_5,
        MarkdownElementTypes.ATX_6,
        MarkdownElementTypes.SETEXT_1,
        MarkdownElementTypes.SETEXT_2,
        ->
            listOf(
                MarkdownSemanticBlock.Heading(
                    level = node.toHeadingLevel(),
                    inlines =
                        parseInlineMarkdownFragment(
                            fragment = node.extractModernHeadingMarkdownFragment(content),
                            references = references,
                        ),
                ),
            )

        MarkdownElementTypes.BLOCK_QUOTE ->
            listOf(
                MarkdownSemanticBlock.BlockQuote(
                    blocks =
                        parseMarkdownSemanticBlocks(
                            nodes = node.children.filter(::isRenderableNestedBlock),
                            content = content,
                            references = references,
                        ),
                ),
            )

        MarkdownElementTypes.UNORDERED_LIST ->
            listOf(
                MarkdownSemanticBlock.ListBlock(
                    ordered = false,
                    startNumber = H1_LEVEL,
                    items =
                        node.children
                            .filter { it.type == MarkdownElementTypes.LIST_ITEM }
                            .map { child -> parseSemanticListItem(child, content, references) },
                ),
            )

        MarkdownElementTypes.ORDERED_LIST ->
            listOf(
                MarkdownSemanticBlock.ListBlock(
                    ordered = true,
                    startNumber = node.resolveOrderedListStart(content),
                    items =
                        node.children
                            .filter { it.type == MarkdownElementTypes.LIST_ITEM }
                            .map { child -> parseSemanticListItem(child, content, references) },
                ),
            )

        MarkdownElementTypes.CODE_FENCE ->
            listOf(
                MarkdownSemanticBlock.CodeBlock(
                    literal = node.extractCodeFenceContent(content).trimEnd('\n'),
                    language =
                        node.children
                            .firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }
                            ?.extractNodeText(content)
                            ?.trim()
                            ?.takeIf(String::isNotEmpty)
                            ?.substringBefore(' '),
                ),
            )

        MarkdownElementTypes.CODE_BLOCK ->
            listOf(
                MarkdownSemanticBlock.CodeBlock(
                    literal = node.extractIndentedCodeContent(content).trimEnd('\n'),
                    language = null,
                ),
            )

        MarkdownTokenTypes.HORIZONTAL_RULE -> listOf(MarkdownSemanticBlock.ThematicBreak)
        GFMElementTypes.TABLE -> listOf(parseSemanticTable(node, content, references))
        MarkdownElementTypes.HTML_BLOCK -> listOf(MarkdownSemanticBlock.HtmlBlock(node.extractNodeText(content)))
        MarkdownElementTypes.LINK_DEFINITION,
        MarkdownTokenTypes.EOL,
        -> emptyList()

        else -> parseMarkdownSemanticBlocks(node.children, content, references)
    }

private fun parseSemanticListItem(
    node: ASTNode,
    content: String,
    references: Map<String, MarkdownLinkReference>,
): MarkdownSemanticListItem =
    MarkdownSemanticListItem(
        checked = node.resolveTaskListChecked(content),
        blocks = parseMarkdownSemanticBlocks(node.children, content, references),
    )

private fun parseSemanticTable(
    node: ASTNode,
    content: String,
    references: Map<String, MarkdownLinkReference>,
): MarkdownSemanticBlock.Table {
    val rows =
        node.children.filter { it.type == GFMElementTypes.HEADER || it.type == GFMElementTypes.ROW }
    val header =
        rows.firstOrNull { it.type == GFMElementTypes.HEADER }
            ?.parseSemanticTableRow(content, references)
            .orEmpty()
    val bodyRows =
        rows
            .filter { it.type == GFMElementTypes.ROW }
            .map { row -> row.parseSemanticTableRow(content, references) }
    return MarkdownSemanticBlock.Table(header = header, rows = bodyRows)
}

private fun ASTNode.parseSemanticTableRow(
    content: String,
    references: Map<String, MarkdownLinkReference>,
): List<MarkdownSemanticTableCell> =
    children
        .filter { it.type == GFMTokenTypes.CELL }
        .map { cell ->
            MarkdownSemanticTableCell(
                inlines = trimEdgeWhitespace(parseSemanticInlines(cell.children, content, references)),
            )
        }

private fun ASTNode.toHeadingLevel(): Int =
    when (type) {
        MarkdownElementTypes.ATX_1,
        MarkdownElementTypes.SETEXT_1,
        -> H1_LEVEL
        MarkdownElementTypes.ATX_2,
        MarkdownElementTypes.SETEXT_2,
        -> H2_LEVEL
        MarkdownElementTypes.ATX_3 -> H3_LEVEL
        MarkdownElementTypes.ATX_4 -> H4_LEVEL
        MarkdownElementTypes.ATX_5 -> H5_LEVEL
        MarkdownElementTypes.ATX_6 -> H6_LEVEL
        else -> H1_LEVEL
    }

private fun ASTNode.resolveOrderedListStart(content: String): Int =
    children
        .firstOrNull { it.type == MarkdownElementTypes.LIST_ITEM }
        ?.children
        ?.firstOrNull { it.type == MarkdownTokenTypes.LIST_NUMBER }
        ?.extractNodeText(content)
        ?.takeWhile(Char::isDigit)
        ?.toIntOrNull()
        ?: H1_LEVEL

private fun ASTNode.resolveTaskListChecked(content: String): Boolean? =
    children
        .firstOrNull { it.type == GFMTokenTypes.CHECK_BOX }
        ?.extractNodeText(content)
        ?.let { checkbox -> checkbox.contains("[x]", ignoreCase = true) }
