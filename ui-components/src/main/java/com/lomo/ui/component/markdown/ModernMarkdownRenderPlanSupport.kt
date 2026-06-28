package com.lomo.ui.component.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode

internal fun limitModernMarkdownRenderPlan(
    plan: ModernMarkdownRenderPlan,
    maxVisibleBlocks: Int,
    mediaPresentationResolver: MarkdownMediaPresentationResolver? = null,
): ModernMarkdownRenderPlan {
    if (plan.hasResolverClaimedGallery(mediaPresentationResolver)) {
        return plan.copy(
            items = buildModernMarkdownRenderItems(
                plan = plan,
                maxVisibleBlocks = maxVisibleBlocks,
                mediaPresentationResolver = mediaPresentationResolver,
            ),
        )
    }

    if (maxVisibleBlocks == Int.MAX_VALUE) {
        return if (plan.items.size == plan.totalBlocks) plan else plan.copy(
            items = buildModernMarkdownRenderItems(
                plan = plan,
                maxVisibleBlocks = maxVisibleBlocks,
                mediaPresentationResolver = mediaPresentationResolver,
            ),
        )
    }

    if (plan.items.size <= maxVisibleBlocks) return plan

    return plan.copy(
        items = buildModernMarkdownRenderItems(
            plan = plan,
            maxVisibleBlocks = maxVisibleBlocks,
            mediaPresentationResolver = mediaPresentationResolver,
        ),
    )
}

internal fun normalizeModernMarkdownRenderPlanForMediaResolver(
    plan: ModernMarkdownRenderPlan,
    mediaPresentationResolver: MarkdownMediaPresentationResolver?,
): ModernMarkdownRenderPlan =
    if (plan.hasResolverClaimedGallery(mediaPresentationResolver)) {
        plan.copy(
            items = buildModernMarkdownRenderItems(
                plan = plan,
                maxVisibleBlocks = Int.MAX_VALUE,
                mediaPresentationResolver = mediaPresentationResolver,
            ),
        )
    } else {
        plan
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
    mediaPresentationResolver: MarkdownMediaPresentationResolver?,
): List<ModernMarkdownRenderItem> {
    val references = parseMarkdownLinkReferences(plan.root, plan.content)
    return buildModernMarkdownRenderItems(
        renderableBlocks =
            plan.root.children.filter { node ->
                isModernRenderableTopLevelBlock(node, plan.content)
            },
        content = plan.content,
        references = references,
        maxVisibleBlocks = maxVisibleBlocks,
        mediaPresentationResolver = mediaPresentationResolver,
    )
}

private fun ModernMarkdownRenderPlan.hasResolverClaimedGallery(
    mediaPresentationResolver: MarkdownMediaPresentationResolver?,
): Boolean =
    mediaPresentationResolver != null &&
        items.any { item ->
            item is ModernMarkdownRenderItem.Gallery &&
                item.images.any { image -> mediaPresentationResolver(image) != null }
        }

internal fun buildModernMarkdownRenderItems(
    renderableBlocks: List<ASTNode>,
    content: String,
    references: Map<String, MarkdownLinkReference>,
    maxVisibleBlocks: Int,
    mediaPresentationResolver: MarkdownMediaPresentationResolver? = null,
): List<ModernMarkdownRenderItem> {
    val items = mutableListOf<ModernMarkdownRenderItem>()
    var index = 0

    while (index < renderableBlocks.size && items.size < maxVisibleBlocks) {
        val gallery =
            consumeModernImageGallery(
                nodes = renderableBlocks,
                startIndex = index,
                content = content,
                mediaPresentationResolver = mediaPresentationResolver,
            )
        if (gallery != null) {
            items += ModernMarkdownRenderItem.Gallery(gallery.images)
            index = gallery.nextIndex
        } else {
            val node = renderableBlocks[index]
            val semanticBlock = parseSemanticBlock(node, content, references).firstOrNull()?.parseHighlights()
            items += ModernMarkdownRenderItem.Block(node = node, semanticBlock = semanticBlock)
            index++
        }
    }

    return items
}

private fun ASTNode.hasModernRenderableContent(content: String): Boolean =
    when {
        type == MarkdownElementTypes.IMAGE -> true
        children.isEmpty() -> extractNodeText(content).any { !it.isWhitespace() }
        type == MarkdownTokenTypes.TEXT || type == MarkdownTokenTypes.WHITE_SPACE ->
            extractNodeText(content).any { !it.isWhitespace() }
        type == MarkdownTokenTypes.EOL || type == MarkdownTokenTypes.HARD_LINE_BREAK -> false
        else -> children.any { child -> child.hasModernRenderableContent(content) }
    }
