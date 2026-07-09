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
        val renderNode: ModernMarkdownRenderNode,
    ) : ModernMarkdownRenderItem {
        val node: ASTNode
            get() = renderNode.node

        val semanticBlock: MarkdownSemanticBlock?
            get() = renderNode.semanticBlock
    }

    data class Gallery(
        val images: List<ModernMarkdownImage>,
    ) : ModernMarkdownRenderItem
}

data class ModernMarkdownRenderKey(
    val path: String,
    val type: String,
    val startOffset: Int,
    val endOffset: Int,
)

sealed interface ModernMarkdownRenderNode {
    val key: ModernMarkdownRenderKey
    val node: ASTNode
    val semanticBlock: MarkdownSemanticBlock?

    data class Paragraph(
        override val key: ModernMarkdownRenderKey,
        override val node: ASTNode,
        val paragraph: MarkdownSemanticBlock.Paragraph,
    ) : ModernMarkdownRenderNode {
        override val semanticBlock: MarkdownSemanticBlock = paragraph
    }

    data class Heading(
        override val key: ModernMarkdownRenderKey,
        override val node: ASTNode,
        val heading: MarkdownSemanticBlock.Heading,
    ) : ModernMarkdownRenderNode {
        override val semanticBlock: MarkdownSemanticBlock = heading
    }

    data class BlockQuote(
        override val key: ModernMarkdownRenderKey,
        override val node: ASTNode,
        val quote: MarkdownSemanticBlock.BlockQuote?,
        val blocks: List<ModernMarkdownRenderNode>,
    ) : ModernMarkdownRenderNode {
        override val semanticBlock: MarkdownSemanticBlock?
            get() = quote
    }

    data class ListBlock(
        override val key: ModernMarkdownRenderKey,
        override val node: ASTNode,
        val list: MarkdownSemanticBlock.ListBlock?,
        val ordered: Boolean,
        val startNumber: Int,
        val items: List<ModernMarkdownListItemRenderNode>,
    ) : ModernMarkdownRenderNode {
        override val semanticBlock: MarkdownSemanticBlock?
            get() = list
    }

    data class CodeBlock(
        override val key: ModernMarkdownRenderKey,
        override val node: ASTNode,
        val codeBlock: MarkdownSemanticBlock.CodeBlock?,
        val fenced: Boolean,
    ) : ModernMarkdownRenderNode {
        override val semanticBlock: MarkdownSemanticBlock?
            get() = codeBlock
    }

    data class Table(
        override val key: ModernMarkdownRenderKey,
        override val node: ASTNode,
        val table: MarkdownSemanticBlock.Table?,
    ) : ModernMarkdownRenderNode {
        override val semanticBlock: MarkdownSemanticBlock?
            get() = table
    }

    data class ThematicBreak(
        override val key: ModernMarkdownRenderKey,
        override val node: ASTNode,
    ) : ModernMarkdownRenderNode {
        override val semanticBlock: MarkdownSemanticBlock = MarkdownSemanticBlock.ThematicBreak
    }

    data class Fallback(
        override val key: ModernMarkdownRenderKey,
        override val node: ASTNode,
        override val semanticBlock: MarkdownSemanticBlock?,
    ) : ModernMarkdownRenderNode
}

data class ModernMarkdownListItemRenderNode(
    val key: ModernMarkdownRenderKey,
    val node: ASTNode,
    val checked: Boolean?,
    val blocks: List<ModernMarkdownRenderNode>,
)

internal fun ModernMarkdownRenderNode.flattenKeys(): List<ModernMarkdownRenderKey> =
    when (this) {
        is ModernMarkdownRenderNode.BlockQuote -> listOf(key) + blocks.flatMap { it.flattenKeys() }
        is ModernMarkdownRenderNode.ListBlock ->
            listOf(key) +
                items.flatMap { item ->
                    listOf(item.key) + item.blocks.flatMap { it.flattenKeys() }
                }
        else -> listOf(key)
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

internal data class SanitizedModernMarkdownContent(
    val content: String,
    val reusableRoot: ASTNode?,
)

internal fun parseModernMarkdownDocument(content: String): ASTNode =
    modernMarkdownParser.buildMarkdownTreeFromString(content)

private val REMINDER_TOKEN_REGEX = Regex(
    "(^|\\s)@\\d{4}-\\d{2}-\\d{2}-\\d{2}:\\d{2}(?:x\\d+)?(?:i\\d+)?(?:r[dw])?(?:\\.(?:done|\\d+))?" +
        "(?=\\s|$|[.,!?;:，。！？；：、)\\]}】）])"
)
private val HORIZONTAL_WHITESPACE_REGEX = Regex("[ \\t]{2,}")
private val SPACE_BEFORE_PUNCTUATION_REGEX = Regex("[ \\t]+(?=[.,!?;:，。！？；：、)\\]}】）])")
private val SPACE_BEFORE_NEWLINE_REGEX = Regex("[ \\t]+(?=\\n)")
private val LEADING_EMPTY_LINES_REGEX = Regex("^(?:[ \\t]*\\n)+")

internal fun stripReminderTokens(input: String): String {
    if (!input.contains('@')) return input
    var changed = false
    val stripped = REMINDER_TOKEN_REGEX.replace(input) { match ->
        changed = true
        match.groupValues[1]
    }
    return if (changed) {
        stripped
            .replace(HORIZONTAL_WHITESPACE_REGEX, " ")
            .replace(SPACE_BEFORE_PUNCTUATION_REGEX, "")
            .replace(SPACE_BEFORE_NEWLINE_REGEX, "\n")
    } else {
        input
    }
}

internal fun sanitizeModernMarkdownKnownTags(
    content: String,
    tags: Iterable<String>,
): SanitizedModernMarkdownContent {
    val sanitizedTags = tags.toList()
    if (sanitizedTags.isEmpty() && !content.contains('@')) {
        return SanitizedModernMarkdownContent(content = content, reusableRoot = null)
    }

    val root = parseModernMarkdownDocument(content)
    val leafRanges = mutableListOf<IntRange>()
    collectModernTagSanitizableLeafRanges(root, leafRanges)
    if (leafRanges.isEmpty()) {
        return SanitizedModernMarkdownContent(content = content, reusableRoot = root)
    }

    val mergedRanges = mergeContiguousRanges(leafRanges)
    val output = StringBuilder(content.length)
    var cursor = 0
    mergedRanges.forEach { range ->
        val start = range.first
        val endExclusive = range.last + 1
        output.append(content, cursor, start)
        val textSegment = content.substring(start, endExclusive)
        val tagsStripped = if (sanitizedTags.isNotEmpty()) {
            MarkdownKnownTagFilter.stripInlineTags(
                input = textSegment,
                tags = sanitizedTags,
            )
        } else {
            textSegment
        }
        val remindersStripped = stripReminderTokens(tagsStripped)
        output.append(remindersStripped)
        cursor = endExclusive
    }
    output.append(content, cursor, content.length)
    val sanitizedContent = output.toString()
    return SanitizedModernMarkdownContent(
        content = sanitizedContent,
        reusableRoot = root.takeIf { sanitizedContent == content },
    )
}

fun createModernMarkdownRenderPlan(
    content: String,
    maxVisibleBlocks: Int = Int.MAX_VALUE,
    knownTagsToStrip: Iterable<String>,
    mediaPresentationResolver: MarkdownMediaPresentationResolver? = null,
): ModernMarkdownRenderPlan {
    val sanitized = sanitizeModernMarkdownKnownTags(content, knownTagsToStrip)
    val sanitizedContent = sanitized.content
    val root = sanitized.reusableRoot ?: parseModernMarkdownDocument(sanitizedContent)
    val references = parseMarkdownLinkReferences(root, sanitizedContent)
    val renderableBlocks =
        root.children.filter { node ->
            isModernRenderableTopLevelBlock(node, sanitizedContent)
        }

    return ModernMarkdownRenderPlan(
        content = sanitizedContent,
        root = root,
        totalBlocks = renderableBlocks.size,
        items = buildModernMarkdownRenderItems(
            renderableBlocks = renderableBlocks,
            content = sanitizedContent,
            references = references,
            maxVisibleBlocks = maxVisibleBlocks,
            mediaPresentationResolver = mediaPresentationResolver,
        ),
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
    if (node.children.isEmpty()) {
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
    // The GFM task-list checkbox is a structural token, not prose. Sanitizing it would run the
    // space-before-punctuation cleanup over "[ ]" and collapse it to "[]", which the parser no longer
    // recognizes as a task. Preserve it verbatim so toggling a todo that carries a reminder/tag keeps
    // its checkbox instead of degrading into a literal "[ ]" bullet.
    node.type == GFMTokenTypes.CHECK_BOX ||
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
    mediaPresentationResolver: MarkdownMediaPresentationResolver? = null,
): ModernImageGallerySequence? {
    val firstImage = nodes[startIndex].toImageOnlyParagraphOrNull(content) ?: return null
    if (mediaPresentationResolver?.invoke(firstImage) != null) return null
    val images = mutableListOf(firstImage)
    var cursor = startIndex + 1
    var shouldContinue = cursor < nodes.size
    while (shouldContinue) {
        val image = nodes[cursor].toImageOnlyParagraphOrNull(content)
        if (image == null || mediaPresentationResolver?.invoke(image) != null) {
            shouldContinue = false
        } else {
            images += image
            cursor++
            shouldContinue = cursor < nodes.size
        }
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
