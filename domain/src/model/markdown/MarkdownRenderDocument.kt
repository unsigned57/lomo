package com.lomo.domain.model.markdown

/**
 * Presentation-safe projection of the Rust-owned Markdown render IR.
 *
 * This type owns no parsing rules. Its constructor enforces the transport-independent type law so
 * data can reconstruct the nested owner IR once and every Kotlin consumer receives the same facts.
 */
data class MarkdownRenderDocument(
    val sourceByteLength: ULong,
    val plainText: String,
    val tagNames: List<String>,
    val attachmentDestinations: List<String>,
    val blocks: List<MarkdownRenderBlock>,
) {
    val schemaVersion: UInt = SCHEMA_VERSION
    val nodeCount: Int = blocks.sumOf(MarkdownRenderBlock::nodeCount)

    init {
        requireContract(sourceByteLength <= MAX_SOURCE_UTF8_BYTES, "render_source_limit_exceeded") {
            "render source exceeds $MAX_SOURCE_UTF8_BYTES UTF-8 bytes"
        }
        requireContract(nodeCount <= MAX_NODE_COUNT, "render_node_limit_exceeded") {
            "render node count exceeds $MAX_NODE_COUNT"
        }
        validateString(plainText)
        tagNames.forEach(::validateString)
        attachmentDestinations.forEach(::validateString)
        blocks.forEach { block -> validateBlock(block = block, parentSpan = null) }
    }

    private fun validateBlock(
        block: MarkdownRenderBlock,
        parentSpan: MarkdownSourceSpan?,
    ) {
        validateNodeSpan(block.sourceSpan, parentSpan)
        when (block) {
            is MarkdownRenderBlock.Paragraph ->
                block.inlines.forEach { inline -> validateInline(inline, block.sourceSpan) }
            is MarkdownRenderBlock.Heading -> {
                requireContract(block.level in 1u..6u, "render_heading_level_invalid") {
                    "heading level must be in 1..6"
                }
                block.inlines.forEach { inline -> validateInline(inline, block.sourceSpan) }
            }
            is MarkdownRenderBlock.BlockQuote ->
                block.blocks.forEach { child -> validateBlock(child, block.sourceSpan) }
            is MarkdownRenderBlock.ListBlock ->
                block.items.forEach { item -> validateListItem(item, block.sourceSpan) }
            is MarkdownRenderBlock.CodeBlock -> {
                validateString(block.literal)
                block.language?.let(::validateString)
            }
            is MarkdownRenderBlock.ThematicBreak -> Unit
            is MarkdownRenderBlock.Table -> {
                block.header.forEach { cell -> validateTableCell(cell, block.sourceSpan) }
                block.rows.forEach { row ->
                    row.forEach { cell -> validateTableCell(cell, block.sourceSpan) }
                }
            }
            is MarkdownRenderBlock.HtmlBlock -> validateString(block.literal)
        }
    }

    private fun validateListItem(
        item: MarkdownRenderListItem,
        parentSpan: MarkdownSourceSpan,
    ) {
        validateNodeSpan(item.sourceSpan, parentSpan)
        item.actionSpan?.let { actionSpan ->
            validateNodeSpan(actionSpan, item.sourceSpan, code = "render_action_span_outside_node")
            requireContract(item.checked != null, "render_task_state_missing") {
                "a task action span requires checked state"
            }
        }
        requireContract(item.actionSpan != null || item.checked == null, "render_task_action_span_missing") {
            "checked state requires a task action span"
        }
        item.blocks.forEach { block -> validateBlock(block, item.sourceSpan) }
    }

    private fun validateTableCell(
        cell: MarkdownRenderTableCell,
        parentSpan: MarkdownSourceSpan,
    ) {
        validateNodeSpan(cell.sourceSpan, parentSpan)
        cell.inlines.forEach { inline -> validateInline(inline, cell.sourceSpan) }
    }

    private fun validateInline(
        inline: MarkdownRenderInline,
        parentSpan: MarkdownSourceSpan,
    ) {
        validateNodeSpan(inline.sourceSpan, parentSpan)
        when (inline) {
            is MarkdownRenderInline.Text -> validateString(inline.text)
            is MarkdownRenderInline.Strong ->
                inline.inlines.forEach { child -> validateInline(child, inline.sourceSpan) }
            is MarkdownRenderInline.Emphasis ->
                inline.inlines.forEach { child -> validateInline(child, inline.sourceSpan) }
            is MarkdownRenderInline.Strikethrough ->
                inline.inlines.forEach { child -> validateInline(child, inline.sourceSpan) }
            is MarkdownRenderInline.Highlight ->
                inline.inlines.forEach { child -> validateInline(child, inline.sourceSpan) }
            is MarkdownRenderInline.Code -> validateString(inline.text)
            is MarkdownRenderInline.Link -> {
                validateString(inline.destination)
                inline.title?.let(::validateString)
                inline.inlines.forEach { child -> validateInline(child, inline.sourceSpan) }
            }
            is MarkdownRenderInline.Image -> {
                validateString(inline.destination)
                inline.title?.let(::validateString)
                validateString(inline.altText)
            }
            is MarkdownRenderInline.Tag -> validateString(inline.name)
            is MarkdownRenderInline.Reminder -> validateString(inline.token)
            is MarkdownRenderInline.WikiReference -> {
                validateString(inline.target)
                inline.inlines.forEach { child -> validateInline(child, inline.sourceSpan) }
            }
            is MarkdownRenderInline.SoftBreak,
            is MarkdownRenderInline.HardBreak,
            -> Unit
            is MarkdownRenderInline.HtmlInline -> validateString(inline.literal)
        }
    }

    private fun validateNodeSpan(
        span: MarkdownSourceSpan,
        parentSpan: MarkdownSourceSpan?,
        code: String = "render_child_span_outside_parent",
    ) {
        requireContract(span.endByte <= sourceByteLength, "render_span_out_of_bounds") {
            "render span exceeds source bytes"
        }
        if (parentSpan != null) {
            requireContract(
                span.startByte >= parentSpan.startByte && span.endByte <= parentSpan.endByte,
                code,
            ) {
                "render child span must be contained by its parent"
            }
        }
    }

    private fun validateString(value: String) {
        requireContract(
            value.encodeToByteArray().size <= MAX_STRING_UTF8_BYTES,
            "render_string_limit_exceeded",
        ) {
            "render string exceeds $MAX_STRING_UTF8_BYTES UTF-8 bytes"
        }
    }

    companion object {
        const val SCHEMA_VERSION: UInt = 1u
        const val MAX_NODE_COUNT: Int = 8192
        const val MAX_NESTING_DEPTH: Int = 64
        const val MAX_STRING_UTF8_BYTES: Int = 256 * 1024
        const val MAX_SOURCE_UTF8_BYTES: ULong = 1_048_576uL
    }
}

data class MarkdownSourceSpan(
    val startByte: ULong,
    val endByte: ULong,
) {
    init {
        requireContract(startByte <= endByte, "render_span_reversed") {
            "render span end must not precede its start"
        }
    }
}

sealed interface MarkdownRenderBlock {
    val sourceSpan: MarkdownSourceSpan

    data class Paragraph(
        override val sourceSpan: MarkdownSourceSpan,
        val inlines: List<MarkdownRenderInline>,
    ) : MarkdownRenderBlock

    data class Heading(
        override val sourceSpan: MarkdownSourceSpan,
        val level: UInt,
        val inlines: List<MarkdownRenderInline>,
    ) : MarkdownRenderBlock

    data class BlockQuote(
        override val sourceSpan: MarkdownSourceSpan,
        val blocks: List<MarkdownRenderBlock>,
    ) : MarkdownRenderBlock

    data class ListBlock(
        override val sourceSpan: MarkdownSourceSpan,
        val ordered: Boolean,
        val startNumber: ULong,
        val items: List<MarkdownRenderListItem>,
    ) : MarkdownRenderBlock

    data class CodeBlock(
        override val sourceSpan: MarkdownSourceSpan,
        val language: String?,
        val literal: String,
    ) : MarkdownRenderBlock

    data class ThematicBreak(
        override val sourceSpan: MarkdownSourceSpan,
    ) : MarkdownRenderBlock

    data class Table(
        override val sourceSpan: MarkdownSourceSpan,
        val header: List<MarkdownRenderTableCell>,
        val rows: List<List<MarkdownRenderTableCell>>,
    ) : MarkdownRenderBlock

    data class HtmlBlock(
        override val sourceSpan: MarkdownSourceSpan,
        val literal: String,
    ) : MarkdownRenderBlock
}

data class MarkdownRenderListItem(
    val sourceSpan: MarkdownSourceSpan,
    val actionSpan: MarkdownSourceSpan?,
    val checked: Boolean?,
    val blocks: List<MarkdownRenderBlock>,
)

data class MarkdownRenderTableCell(
    val sourceSpan: MarkdownSourceSpan,
    val inlines: List<MarkdownRenderInline>,
)

sealed interface MarkdownRenderInline {
    val sourceSpan: MarkdownSourceSpan

    data class Text(
        override val sourceSpan: MarkdownSourceSpan,
        val text: String,
    ) : MarkdownRenderInline

    data class Strong(
        override val sourceSpan: MarkdownSourceSpan,
        val inlines: List<MarkdownRenderInline>,
    ) : MarkdownRenderInline

    data class Emphasis(
        override val sourceSpan: MarkdownSourceSpan,
        val inlines: List<MarkdownRenderInline>,
    ) : MarkdownRenderInline

    data class Strikethrough(
        override val sourceSpan: MarkdownSourceSpan,
        val inlines: List<MarkdownRenderInline>,
    ) : MarkdownRenderInline

    data class Highlight(
        override val sourceSpan: MarkdownSourceSpan,
        val inlines: List<MarkdownRenderInline>,
    ) : MarkdownRenderInline

    data class Code(
        override val sourceSpan: MarkdownSourceSpan,
        val text: String,
    ) : MarkdownRenderInline

    data class Link(
        override val sourceSpan: MarkdownSourceSpan,
        val destination: String,
        val title: String?,
        val inlines: List<MarkdownRenderInline>,
    ) : MarkdownRenderInline

    data class Image(
        override val sourceSpan: MarkdownSourceSpan,
        val destination: String,
        val title: String?,
        val altText: String,
    ) : MarkdownRenderInline

    data class Tag(
        override val sourceSpan: MarkdownSourceSpan,
        val name: String,
    ) : MarkdownRenderInline

    data class Reminder(
        override val sourceSpan: MarkdownSourceSpan,
        val token: String,
    ) : MarkdownRenderInline

    data class WikiReference(
        override val sourceSpan: MarkdownSourceSpan,
        val target: String,
        val inlines: List<MarkdownRenderInline>,
    ) : MarkdownRenderInline

    data class SoftBreak(
        override val sourceSpan: MarkdownSourceSpan,
    ) : MarkdownRenderInline

    data class HardBreak(
        override val sourceSpan: MarkdownSourceSpan,
    ) : MarkdownRenderInline

    data class HtmlInline(
        override val sourceSpan: MarkdownSourceSpan,
        val literal: String,
    ) : MarkdownRenderInline
}

class MarkdownRenderContractException(
    val code: String,
    message: String,
) : IllegalArgumentException(message)

private inline fun requireContract(
    condition: Boolean,
    code: String,
    message: () -> String,
) {
    if (!condition) throw MarkdownRenderContractException(code, message())
}

private fun MarkdownRenderBlock.nodeCount(): Int =
    1 +
        when (this) {
            is MarkdownRenderBlock.Paragraph -> inlines.sumOf(MarkdownRenderInline::nodeCount)
            is MarkdownRenderBlock.Heading -> inlines.sumOf(MarkdownRenderInline::nodeCount)
            is MarkdownRenderBlock.BlockQuote -> blocks.sumOf(MarkdownRenderBlock::nodeCount)
            is MarkdownRenderBlock.ListBlock -> items.sumOf(MarkdownRenderListItem::nodeCount)
            is MarkdownRenderBlock.CodeBlock,
            is MarkdownRenderBlock.ThematicBreak,
            is MarkdownRenderBlock.HtmlBlock,
            -> 0
            is MarkdownRenderBlock.Table ->
                header.sumOf(MarkdownRenderTableCell::nodeCount) +
                    rows.sumOf { row -> row.sumOf(MarkdownRenderTableCell::nodeCount) }
        }

private fun MarkdownRenderListItem.nodeCount(): Int =
    1 + blocks.sumOf(MarkdownRenderBlock::nodeCount)

private fun MarkdownRenderTableCell.nodeCount(): Int =
    1 + inlines.sumOf(MarkdownRenderInline::nodeCount)

private fun MarkdownRenderInline.nodeCount(): Int =
    1 +
        when (this) {
            is MarkdownRenderInline.Strong -> inlines.sumOf(MarkdownRenderInline::nodeCount)
            is MarkdownRenderInline.Emphasis -> inlines.sumOf(MarkdownRenderInline::nodeCount)
            is MarkdownRenderInline.Strikethrough -> inlines.sumOf(MarkdownRenderInline::nodeCount)
            is MarkdownRenderInline.Highlight -> inlines.sumOf(MarkdownRenderInline::nodeCount)
            is MarkdownRenderInline.Link -> inlines.sumOf(MarkdownRenderInline::nodeCount)
            is MarkdownRenderInline.WikiReference -> inlines.sumOf(MarkdownRenderInline::nodeCount)
            is MarkdownRenderInline.Text,
            is MarkdownRenderInline.Code,
            is MarkdownRenderInline.Image,
            is MarkdownRenderInline.Tag,
            is MarkdownRenderInline.Reminder,
            is MarkdownRenderInline.SoftBreak,
            is MarkdownRenderInline.HardBreak,
            is MarkdownRenderInline.HtmlInline,
            -> 0
        }
