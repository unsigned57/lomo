package com.lomo.ui.component.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode

internal fun limitModernMarkdownRenderPlan(
    plan: ModernMarkdownRenderPlan,
    maxVisibleBlocks: Int,
): ModernMarkdownRenderPlan {
    if (maxVisibleBlocks == Int.MAX_VALUE) {
        return if (plan.items.size == plan.totalBlocks) plan else plan.copy(
            items = buildModernMarkdownRenderItems(plan, maxVisibleBlocks),
        )
    }

    if (plan.items.size <= maxVisibleBlocks) return plan

    return plan.copy(
        items = buildModernMarkdownRenderItems(plan, maxVisibleBlocks),
    )
}

internal fun isModernRenderableTopLevelBlock(
    node: ASTNode,
    content: String,
): Boolean =
    node.type != MarkdownTokenTypes.EOL &&
        node.type != MarkdownTokenTypes.WHITE_SPACE &&
        !(node.type == MarkdownTokenTypes.TEXT && node.extractNodeText(content).isBlank()) &&
        !isModernPrunableEmptyBlock(node, content)

private fun isModernPrunableEmptyBlock(
    node: ASTNode,
    content: String,
): Boolean = node.type == MarkdownElementTypes.PARAGRAPH && !node.hasModernRenderableContent(content)

private fun buildModernMarkdownRenderItems(
    plan: ModernMarkdownRenderPlan,
    maxVisibleBlocks: Int,
): List<ModernMarkdownRenderItem> =
    buildModernMarkdownRenderItems(
        renderableBlocks =
            plan.root.children.filter { node ->
                isModernRenderableTopLevelBlock(node, plan.content)
            },
        content = plan.content,
        maxVisibleBlocks = maxVisibleBlocks,
    )

internal fun buildModernMarkdownRenderItems(
    renderableBlocks: List<ASTNode>,
    content: String,
    maxVisibleBlocks: Int,
): List<ModernMarkdownRenderItem> {
    val items = mutableListOf<ModernMarkdownRenderItem>()
    var index = 0

    while (index < renderableBlocks.size && items.size < maxVisibleBlocks) {
        val gallery = consumeModernImageGallery(renderableBlocks, index, content)
        if (gallery != null) {
            items += ModernMarkdownRenderItem.Gallery(gallery.images)
            index = gallery.nextIndex
        } else {
            items += ModernMarkdownRenderItem.Block(renderableBlocks[index])
            index++
        }
    }

    return items
}

private fun ASTNode.hasModernRenderableContent(content: String): Boolean =
    when {
        type == MarkdownElementTypes.IMAGE -> true
        type == MarkdownTokenTypes.TEXT || type == MarkdownTokenTypes.WHITE_SPACE ->
            extractNodeText(content).any { !it.isWhitespace() }
        type == MarkdownTokenTypes.EOL || type == MarkdownTokenTypes.HARD_LINE_BREAK -> false
        else -> children.any { child -> child.hasModernRenderableContent(content) }
    }
