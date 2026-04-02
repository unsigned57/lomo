package com.lomo.ui.component.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser as JetBrainsMarkdownParser

private val modernMarkdownParser by lazy {
    JetBrainsMarkdownParser(GFMFlavourDescriptor())
}

data class ModernMarkdownRenderPlan(
    val content: String,
    val root: ASTNode,
    val totalBlocks: Int,
    val items: List<ModernMarkdownRenderItem>,
)

sealed interface ModernMarkdownRenderItem {
    data class Block(
        val node: ASTNode,
    ) : ModernMarkdownRenderItem

    data class Gallery(
        val images: List<ModernMarkdownImage>,
    ) : ModernMarkdownRenderItem
}

data class ModernMarkdownImage(
    val destination: String,
    val title: String? = null,
)

internal data class ModernTaskListPresentation(
    val isTask: Boolean,
    val sourceLine: Int,
    val effectiveChecked: Boolean,
)

internal fun parseModernMarkdownDocument(content: String): ASTNode =
    modernMarkdownParser.buildMarkdownTreeFromString(content)

internal fun sanitizeModernMarkdownKnownTags(
    content: String,
    tags: Iterable<String>,
): String {
    val sanitizedTags = tags.toList()
    if (sanitizedTags.isEmpty()) return content

    val root = parseModernMarkdownDocument(content)
    val leafRanges = mutableListOf<IntRange>()
    collectModernTagSanitizableLeafRanges(root, leafRanges)
    if (leafRanges.isEmpty()) return content

    val mergedRanges = mergeContiguousRanges(leafRanges)
    val output = StringBuilder(content.length)
    var cursor = 0
    mergedRanges.forEach { range ->
        val start = range.first
        val endExclusive = range.last + 1
        output.append(content, cursor, start)
        output.append(
            MarkdownKnownTagFilter.stripInlineTags(
                input = content.substring(start, endExclusive),
                tags = sanitizedTags,
            ),
        )
        cursor = endExclusive
    }
    output.append(content, cursor, content.length)
    return output.toString()
}

fun createModernMarkdownRenderPlan(
    content: String,
    maxVisibleBlocks: Int = Int.MAX_VALUE,
    knownTagsToStrip: Iterable<String>,
): ModernMarkdownRenderPlan {
    val sanitizedContent = sanitizeModernMarkdownKnownTags(content, knownTagsToStrip)
    val root = parseModernMarkdownDocument(sanitizedContent)
    val renderableBlocks =
        root.children.filter { node ->
            isModernRenderableTopLevelBlock(node, sanitizedContent)
        }

    return ModernMarkdownRenderPlan(
        content = sanitizedContent,
        root = root,
        totalBlocks = renderableBlocks.size,
        items = buildModernMarkdownRenderItems(renderableBlocks, sanitizedContent, maxVisibleBlocks),
    )
}

internal fun resolveModernTaskListPresentation(
    content: String,
    listItemNode: ASTNode,
    todoOverrides: Map<Int, Boolean>,
): ModernTaskListPresentation {
    val sourceLine = content.countLineIndexBeforeOffset(listItemNode.startOffset)
    val checkboxNode = listItemNode.children.firstOrNull { it.type == GFMTokenTypes.CHECK_BOX }
    val parsedChecked = checkboxNode?.extractNodeText(content)?.contains("[x]", ignoreCase = true) == true
    return ModernTaskListPresentation(
        isTask = checkboxNode != null,
        sourceLine = sourceLine,
        effectiveChecked = todoOverrides[sourceLine] ?: parsedChecked,
    )
}

internal data class ModernImageGallerySequence(
    val images: List<ModernMarkdownImage>,
    val nextIndex: Int,
)

private fun collectModernTagSanitizableLeafRanges(
    node: ASTNode,
    ranges: MutableList<IntRange>,
) {
    if (shouldSkipModernTagSanitizing(node)) return
    if (node.type == MarkdownTokenTypes.TEXT || node.type == MarkdownTokenTypes.WHITE_SPACE) {
        if (node.startOffset < node.endOffset) {
            ranges += node.startOffset until node.endOffset
        }
        return
    }
    node.children.forEach { child ->
        collectModernTagSanitizableLeafRanges(child, ranges)
    }
}

private fun shouldSkipModernTagSanitizing(node: ASTNode): Boolean =
    node.type == MarkdownElementTypes.ATX_1 ||
        node.type == MarkdownElementTypes.ATX_2 ||
        node.type == MarkdownElementTypes.ATX_3 ||
        node.type == MarkdownElementTypes.ATX_4 ||
        node.type == MarkdownElementTypes.ATX_5 ||
        node.type == MarkdownElementTypes.ATX_6 ||
        node.type == MarkdownElementTypes.SETEXT_1 ||
        node.type == MarkdownElementTypes.SETEXT_2 ||
        node.type == MarkdownElementTypes.INLINE_LINK ||
        node.type == MarkdownElementTypes.FULL_REFERENCE_LINK ||
        node.type == MarkdownElementTypes.SHORT_REFERENCE_LINK ||
        node.type == MarkdownElementTypes.AUTOLINK ||
        node.type == MarkdownElementTypes.CODE_FENCE ||
        node.type == MarkdownElementTypes.CODE_BLOCK ||
        node.type == MarkdownElementTypes.CODE_SPAN

private fun mergeContiguousRanges(ranges: List<IntRange>): List<IntRange> {
    if (ranges.isEmpty()) return emptyList()
    val sortedRanges = ranges.sortedBy(IntRange::first)
    val merged = mutableListOf<IntRange>()
    var currentStart = sortedRanges.first().first
    var currentEnd = sortedRanges.first().last

    sortedRanges.drop(1).forEach { range ->
        if (range.first <= currentEnd + 1) {
            currentEnd = maxOf(currentEnd, range.last)
        } else {
            merged += currentStart..currentEnd
            currentStart = range.first
            currentEnd = range.last
        }
    }
    merged += currentStart..currentEnd
    return merged
}

internal fun consumeModernImageGallery(
    nodes: List<ASTNode>,
    startIndex: Int,
    content: String,
): ModernImageGallerySequence? {
    val firstImage = nodes[startIndex].toImageOnlyParagraphOrNull(content) ?: return null
    val images = mutableListOf(firstImage)
    var cursor = startIndex + 1
    while (cursor < nodes.size) {
        val image = nodes[cursor].toImageOnlyParagraphOrNull(content) ?: break
        images += image
        cursor++
    }
    return if (images.size > 1) {
        ModernImageGallerySequence(
            images = images,
            nextIndex = cursor,
        )
    } else {
        null
    }
}

private fun ASTNode.toImageOnlyParagraphOrNull(content: String): ModernMarkdownImage? {
    if (type != MarkdownElementTypes.PARAGRAPH) return null
    var image: ModernMarkdownImage? = null
    for (child in children) {
        when {
            child.type == MarkdownElementTypes.IMAGE -> {
                if (image != null) return null
                image = child.extractModernImageOrNull(content) ?: return null
            }

            child.type == MarkdownTokenTypes.WHITE_SPACE ||
                child.type == MarkdownTokenTypes.EOL ||
                child.type == MarkdownTokenTypes.HARD_LINE_BREAK ||
                (child.type == MarkdownTokenTypes.TEXT && child.extractNodeText(content).isBlank()) -> Unit

            else -> return null
        }
    }
    return image
}
