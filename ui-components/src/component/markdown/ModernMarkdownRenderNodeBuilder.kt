package com.lomo.ui.component.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes

internal fun buildModernMarkdownRenderNode(
    node: ASTNode,
    content: String,
    references: Map<String, MarkdownLinkReference>,
    path: String,
): ModernMarkdownRenderNode {
    val semanticBlock = parseSemanticBlock(node, content, references).firstOrNull()?.parseHighlights()
    return when (node.type) {
        MarkdownElementTypes.PARAGRAPH ->
            (semanticBlock as? MarkdownSemanticBlock.Paragraph)?.let { paragraph ->
                ModernMarkdownRenderNode.Paragraph(
                    key = node.renderKey(path = path, type = "paragraph"),
                    node = node,
                    paragraph = paragraph,
                )
            } ?: node.fallbackRenderNode(path = path, semanticBlock = semanticBlock)

        MarkdownElementTypes.ATX_1,
        MarkdownElementTypes.ATX_2,
        MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4,
        MarkdownElementTypes.ATX_5,
        MarkdownElementTypes.ATX_6,
        MarkdownElementTypes.SETEXT_1,
        MarkdownElementTypes.SETEXT_2,
        ->
            (semanticBlock as? MarkdownSemanticBlock.Heading)?.let { heading ->
                ModernMarkdownRenderNode.Heading(
                    key = node.renderKey(path = path, type = "heading"),
                    node = node,
                    heading = heading,
                )
            } ?: node.fallbackRenderNode(path = path, semanticBlock = semanticBlock)

        MarkdownElementTypes.BLOCK_QUOTE ->
            ModernMarkdownRenderNode.BlockQuote(
                key = node.renderKey(path = path, type = "blockquote"),
                node = node,
                quote = semanticBlock as? MarkdownSemanticBlock.BlockQuote,
                blocks =
                    node.children
                        .filter { child -> isRenderableNestedBlock(child, content) }
                        .mapIndexed { index, child ->
                            buildModernMarkdownRenderNode(
                                node = child,
                                content = content,
                                references = references,
                                path = "$path.quote.$index",
                            )
                        },
            )

        MarkdownElementTypes.UNORDERED_LIST,
        MarkdownElementTypes.ORDERED_LIST,
        ->
            ModernMarkdownRenderNode.ListBlock(
                key =
                    node.renderKey(
                        path = path,
                        type = if (node.type == MarkdownElementTypes.ORDERED_LIST) "ordered-list" else "unordered-list",
                    ),
                node = node,
                list = semanticBlock as? MarkdownSemanticBlock.ListBlock,
                ordered = node.type == MarkdownElementTypes.ORDERED_LIST,
                startNumber = node.resolveMarkdownOrderedListStart(content),
                items =
                    node.children
                        .filter { it.type == MarkdownElementTypes.LIST_ITEM }
                        .mapIndexed { index, itemNode ->
                            buildModernMarkdownListItemRenderNode(
                                node = itemNode,
                                content = content,
                                references = references,
                                path = "$path.item.$index",
                            )
                        },
            )

        MarkdownElementTypes.CODE_FENCE ->
            ModernMarkdownRenderNode.CodeBlock(
                key = node.renderKey(path = path, type = "code-fence"),
                node = node,
                codeBlock = semanticBlock as? MarkdownSemanticBlock.CodeBlock,
                fenced = true,
            )

        MarkdownElementTypes.CODE_BLOCK ->
            ModernMarkdownRenderNode.CodeBlock(
                key = node.renderKey(path = path, type = "code-block"),
                node = node,
                codeBlock = semanticBlock as? MarkdownSemanticBlock.CodeBlock,
                fenced = false,
            )

        MarkdownTokenTypes.HORIZONTAL_RULE ->
            ModernMarkdownRenderNode.ThematicBreak(
                key = node.renderKey(path = path, type = "thematic-break"),
                node = node,
            )

        GFMElementTypes.TABLE ->
            ModernMarkdownRenderNode.Table(
                key = node.renderKey(path = path, type = "table"),
                node = node,
                table = semanticBlock as? MarkdownSemanticBlock.Table,
            )

        else -> node.fallbackRenderNode(path = path, semanticBlock = semanticBlock)
    }
}

private fun buildModernMarkdownListItemRenderNode(
    node: ASTNode,
    content: String,
    references: Map<String, MarkdownLinkReference>,
    path: String,
): ModernMarkdownListItemRenderNode =
    ModernMarkdownListItemRenderNode(
        key = node.renderKey(path = path, type = "list-item"),
        node = node,
        checked = node.resolveMarkdownTaskListChecked(content),
        blocks =
            node.children
                .filter { child -> isRenderableListItemChild(child, content) }
                .mapIndexed { index, child ->
                    buildModernMarkdownRenderNode(
                        node = child,
                        content = content,
                        references = references,
                        path = "$path.block.$index",
                    )
                },
    )

private fun ASTNode.fallbackRenderNode(
    path: String,
    semanticBlock: MarkdownSemanticBlock?,
): ModernMarkdownRenderNode =
    ModernMarkdownRenderNode.Fallback(
        key = renderKey(path = path, type = "fallback"),
        node = this,
        semanticBlock = semanticBlock,
    )

private fun ASTNode.renderKey(
    path: String,
    type: String,
): ModernMarkdownRenderKey =
    ModernMarkdownRenderKey(
        path = path,
        type = type,
        startOffset = startOffset,
        endOffset = endOffset,
    )
