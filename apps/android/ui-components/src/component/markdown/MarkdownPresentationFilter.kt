package com.lomo.ui.component.markdown

import com.lomo.domain.model.markdown.MarkdownRenderBlock
import com.lomo.domain.model.markdown.MarkdownRenderInline
import com.lomo.domain.model.markdown.MarkdownSourceSpan

internal object MarkdownPresentationFilter {
    private val HORIZONTAL_WHITESPACE_REGEX = Regex("[ \\t]{2,}")
    private val SPACE_BEFORE_PUNCTUATION_REGEX = Regex("[ \\t]+(?=[.,!?;:，。！？；：、)\\]}】）])")

    fun filterBlock(
        block: MarkdownRenderBlock,
        policy: MarkdownPresentationPolicy,
    ): MarkdownRenderBlock? =
        when (block) {
            is MarkdownRenderBlock.Paragraph -> {
                val inlines = filterInlines(block.inlines, policy)
                if (policy.pruneEmptyBlocks && !hasRenderableInlines(inlines, policy)) {
                    null
                } else {
                    block.copy(inlines = inlines)
                }
            }
            is MarkdownRenderBlock.Heading -> {
                val inlines = filterInlines(block.inlines, policy)
                if (policy.pruneEmptyBlocks && !hasRenderableInlines(inlines, policy)) {
                    null
                } else {
                    block.copy(inlines = inlines)
                }
            }
            is MarkdownRenderBlock.BlockQuote -> {
                val childBlocks = block.blocks.mapNotNull { child -> filterBlock(child, policy) }
                if (policy.pruneEmptyBlocks && childBlocks.isEmpty()) {
                    null
                } else {
                    block.copy(blocks = childBlocks)
                }
            }
            is MarkdownRenderBlock.ListBlock -> {
                val items =
                    block.items.mapNotNull { item ->
                        val childBlocks = item.blocks.mapNotNull { child -> filterBlock(child, policy) }
                        if (policy.pruneEmptyBlocks && childBlocks.isEmpty() && item.actionSpan == null) {
                            null
                        } else {
                            item.copy(blocks = childBlocks)
                        }
                    }
                if (policy.pruneEmptyBlocks && items.isEmpty()) {
                    null
                } else {
                    block.copy(items = items)
                }
            }
            is MarkdownRenderBlock.Table -> {
                val header =
                    block.header.map { cell ->
                        cell.copy(inlines = filterInlines(cell.inlines, policy))
                    }
                val rows =
                    block.rows.map { row ->
                        row.map { cell ->
                            cell.copy(inlines = filterInlines(cell.inlines, policy))
                        }
                    }
                block.copy(header = header, rows = rows)
            }
            is MarkdownRenderBlock.CodeBlock,
            is MarkdownRenderBlock.ThematicBreak,
            is MarkdownRenderBlock.HtmlBlock,
            -> block
        }

    fun filterInlines(
        inlines: List<MarkdownRenderInline>,
        policy: MarkdownPresentationPolicy,
    ): List<MarkdownRenderInline> {
        if (!policy.hideTags && !policy.hideReminders) {
            return inlines
        }
        val rawFiltered = inlines.mapNotNull { inline -> filterInline(inline, policy) }
        if (!policy.normalizeWhitespace) {
            return rawFiltered
        }
        val merged = mergeAdjacentTextNodes(rawFiltered)
        val trimmedBreaks = trimBreakNodesAndWhitespace(merged)
        val collapsedBreaks = collapseConsecutiveBreaks(trimmedBreaks)
        val normalized =
            collapsedBreaks.mapIndexedNotNull { index, inline ->
                normalizeInlineText(
                    inline = inline,
                    isFirst = index == 0,
                    isLast = index == collapsedBreaks.lastIndex,
                )
            }
        return trimBreakNodesAndWhitespace(mergeAdjacentTextNodes(normalized))
    }

    private fun filterInline(
        inline: MarkdownRenderInline,
        policy: MarkdownPresentationPolicy,
    ): MarkdownRenderInline? =
        when (inline) {
            is MarkdownRenderInline.Tag -> if (policy.hideTags) null else inline
            is MarkdownRenderInline.Reminder -> if (policy.hideReminders) null else inline
            is MarkdownRenderInline.Strong -> {
                val children = filterInlines(inline.inlines, policy)
                if (children.isEmpty()) null else inline.copy(inlines = children)
            }
            is MarkdownRenderInline.Emphasis -> {
                val children = filterInlines(inline.inlines, policy)
                if (children.isEmpty()) null else inline.copy(inlines = children)
            }
            is MarkdownRenderInline.Strikethrough -> {
                val children = filterInlines(inline.inlines, policy)
                if (children.isEmpty()) null else inline.copy(inlines = children)
            }
            is MarkdownRenderInline.Highlight -> {
                val children = filterInlines(inline.inlines, policy)
                if (children.isEmpty()) null else inline.copy(inlines = children)
            }
            is MarkdownRenderInline.Link -> {
                val children = filterInlines(inline.inlines, policy)
                inline.copy(inlines = children)
            }
            is MarkdownRenderInline.WikiReference -> {
                val children = filterInlines(inline.inlines, policy)
                inline.copy(inlines = children)
            }
            is MarkdownRenderInline.Text,
            is MarkdownRenderInline.Code,
            is MarkdownRenderInline.Image,
            is MarkdownRenderInline.SoftBreak,
            is MarkdownRenderInline.HardBreak,
            is MarkdownRenderInline.HtmlInline,
            -> inline
        }

    private fun trimBreakNodesAndWhitespace(inlines: List<MarkdownRenderInline>): List<MarkdownRenderInline> {
        val startIndex = inlines.indexOfFirst { inline -> !isBreakOrBlank(inline) }
        if (startIndex == -1) return emptyList()
        val lastIndex = inlines.indexOfLast { inline -> !isBreakOrBlank(inline) }
        val slice = inlines.subList(startIndex, lastIndex + 1).toMutableList()

        val first = slice.first()
        if (first is MarkdownRenderInline.Text) {
            val trimmed = first.text.trimStart()
            if (trimmed.isEmpty()) {
                slice.removeAt(0)
            } else if (trimmed != first.text) {
                slice[0] = first.copy(text = trimmed)
            }
        }
        if (slice.isEmpty()) return emptyList()

        val last = slice.last()
        if (last is MarkdownRenderInline.Text) {
            val trimmed = last.text.trimEnd()
            if (trimmed.isEmpty()) {
                slice.removeAt(slice.lastIndex)
            } else if (trimmed != last.text) {
                slice[slice.lastIndex] = last.copy(text = trimmed)
            }
        }

        return slice
    }

    private fun isBreakOrBlank(inline: MarkdownRenderInline): Boolean =
        when (inline) {
            is MarkdownRenderInline.SoftBreak,
            is MarkdownRenderInline.HardBreak,
            -> true
            is MarkdownRenderInline.Text -> inline.text.isBlank()
            else -> false
        }

    private fun collapseConsecutiveBreaks(inlines: List<MarkdownRenderInline>): List<MarkdownRenderInline> {
        if (inlines.isEmpty()) return inlines
        val result = mutableListOf<MarkdownRenderInline>()
        var previousWasBreak = false

        for (inline in inlines) {
            val isBreak = inline is MarkdownRenderInline.SoftBreak || inline is MarkdownRenderInline.HardBreak
            if (isBreak) {
                if (!previousWasBreak) {
                    result.add(inline)
                    previousWasBreak = true
                }
            } else {
                previousWasBreak = false
                result.add(inline)
            }
        }
        return result
    }

    private fun mergeAdjacentTextNodes(inlines: List<MarkdownRenderInline>): List<MarkdownRenderInline> {
        if (inlines.isEmpty()) return inlines
        val result = mutableListOf<MarkdownRenderInline>()
        for (inline in inlines) {
            val last = result.lastOrNull()
            if (last is MarkdownRenderInline.Text && inline is MarkdownRenderInline.Text) {
                result[result.lastIndex] =
                    MarkdownRenderInline.Text(
                        sourceSpan = MarkdownSourceSpan(last.sourceSpan.startByte, inline.sourceSpan.endByte),
                        text = last.text + inline.text,
                    )
            } else {
                result.add(inline)
            }
        }
        return result
    }

    private fun normalizeInlineText(
        inline: MarkdownRenderInline,
        isFirst: Boolean,
        isLast: Boolean,
    ): MarkdownRenderInline? {
        if (inline !is MarkdownRenderInline.Text) return inline
        var text = inline.text.replace(HORIZONTAL_WHITESPACE_REGEX, " ")
        text = text.replace(SPACE_BEFORE_PUNCTUATION_REGEX, "")
        if (isFirst) {
            text = text.trimStart(' ', '\t')
        }
        if (isLast) {
            text = text.trimEnd(' ', '\t')
        }
        return if (text.isEmpty()) {
            null
        } else {
            inline.copy(text = text)
        }
    }

    private fun hasRenderableInlines(
        inlines: List<MarkdownRenderInline>,
        policy: MarkdownPresentationPolicy,
    ): Boolean =
        inlines.any { inline ->
            when (inline) {
                is MarkdownRenderInline.Text -> inline.text.isNotBlank()
                is MarkdownRenderInline.Image -> true
                is MarkdownRenderInline.Code -> inline.text.isNotBlank()
                is MarkdownRenderInline.Strong -> hasRenderableInlines(inline.inlines, policy)
                is MarkdownRenderInline.Emphasis -> hasRenderableInlines(inline.inlines, policy)
                is MarkdownRenderInline.Strikethrough -> hasRenderableInlines(inline.inlines, policy)
                is MarkdownRenderInline.Highlight -> hasRenderableInlines(inline.inlines, policy)
                is MarkdownRenderInline.Link -> hasRenderableInlines(inline.inlines, policy)
                is MarkdownRenderInline.WikiReference -> true
                is MarkdownRenderInline.Tag -> !policy.hideTags && inline.name.isNotBlank()
                is MarkdownRenderInline.Reminder -> !policy.hideReminders && inline.token.isNotBlank()
                is MarkdownRenderInline.SoftBreak,
                is MarkdownRenderInline.HardBreak,
                is MarkdownRenderInline.HtmlInline,
                -> false
            }
        }
}
