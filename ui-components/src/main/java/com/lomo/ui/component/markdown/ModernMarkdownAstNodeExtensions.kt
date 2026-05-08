package com.lomo.ui.component.markdown

import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
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

internal fun isRenderableNestedBlock(node: ASTNode): Boolean = node.type != MarkdownTokenTypes.EOL
