package com.lomo.ui.component.markdown

import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

internal fun ASTNode.extractCodeFenceContent(content: String): String =
    children
        .filter { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }
        .joinToString(separator = "\n") { it.extractNodeText(content) }

internal fun ASTNode.extractIndentedCodeContent(content: String): String =
    children
        .filter { it.type == MarkdownTokenTypes.CODE_LINE }
        .joinToString(separator = "\n") { it.extractNodeText(content) }

internal fun ASTNode.extractModernMarkdownTableRows(content: String): List<ModernMarkdownTableRow> =
    children
        .filter { it.type == GFMElementTypes.HEADER || it.type == GFMElementTypes.ROW }
        .mapNotNull { row ->
            row.children
                .filter { it.type == GFMTokenTypes.CELL }
                .map { cell -> cell.extractNodeText(content).trim().trim('|').trim() }
                .filter { it.isNotBlank() }
                .takeIf { it.isNotEmpty() }
                ?.let(::ModernMarkdownTableRow)
        }

internal fun isRenderableNestedBlock(
    node: ASTNode,
    content: String,
): Boolean =
    node.type != MarkdownTokenTypes.GT &&
        !node.isBlockQuoteMarkerOnly(content) &&
        isModernRenderableTopLevelBlock(node = node, content = content)

private fun ASTNode.isBlockQuoteMarkerOnly(content: String): Boolean =
    (type == MarkdownElementTypes.BLOCK_QUOTE || type.toString() == "Markdown:BLOCK_QUOTE") &&
        extractNodeText(content).trim() == ">"

internal fun isRenderableListItemChild(
    node: ASTNode,
    content: String,
): Boolean =
    node.type != MarkdownTokenTypes.LIST_BULLET &&
        node.type != MarkdownTokenTypes.LIST_NUMBER &&
        node.type != GFMTokenTypes.CHECK_BOX &&
        isRenderableNestedBlock(node = node, content = content)

internal fun ASTNode.extractOrderedListMarker(content: String): String? =
    children
        .firstOrNull { it.type == MarkdownTokenTypes.LIST_NUMBER }
        ?.extractNodeText(content)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

internal fun ASTNode.resolveMarkdownOrderedListStart(content: String): Int =
    children
        .firstOrNull { it.type == MarkdownElementTypes.LIST_ITEM }
        ?.children
        ?.firstOrNull { it.type == MarkdownTokenTypes.LIST_NUMBER }
        ?.extractNodeText(content)
        ?.takeWhile(Char::isDigit)
        ?.toIntOrNull()
        ?: 1

internal fun ASTNode.resolveMarkdownTaskListChecked(content: String): Boolean? =
    children
        .firstOrNull { it.type == GFMTokenTypes.CHECK_BOX }
        ?.extractNodeText(content)
        ?.let { checkbox -> checkbox.contains("[x]", ignoreCase = true) }
