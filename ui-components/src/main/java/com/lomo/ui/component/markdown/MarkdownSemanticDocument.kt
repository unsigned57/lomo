package com.lomo.ui.component.markdown

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough as CommonMarkStrikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.CustomNode
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

data class MarkdownSemanticDocument(
    val blocks: List<MarkdownSemanticBlock>,
)

sealed interface MarkdownSemanticBlock {
    val plainText: String

    data class Paragraph(
        val inlines: List<MarkdownSemanticInline>,
    ) : MarkdownSemanticBlock {
        override val plainText: String
            get() = inlines.plainText()
    }

    data class Heading(
        val level: Int,
        val inlines: List<MarkdownSemanticInline>,
    ) : MarkdownSemanticBlock {
        override val plainText: String
            get() = inlines.plainText()
    }

    data class BlockQuote(
        val blocks: List<MarkdownSemanticBlock>,
    ) : MarkdownSemanticBlock {
        override val plainText: String
            get() = blocks.joinToString(separator = "\n") { it.plainText }
    }

    data class ListBlock(
        val ordered: Boolean,
        val startNumber: Int,
        val items: List<MarkdownSemanticListItem>,
    ) : MarkdownSemanticBlock {
        override val plainText: String
            get() = items.joinToString(separator = "\n") { it.plainText }
    }

    data class CodeBlock(
        val literal: String,
        val language: String?,
    ) : MarkdownSemanticBlock {
        override val plainText: String
            get() = literal
    }

    data object ThematicBreak : MarkdownSemanticBlock {
        override val plainText: String = ""
    }

    data class Table(
        val header: List<MarkdownSemanticTableCell>,
        val rows: List<List<MarkdownSemanticTableCell>>,
    ) : MarkdownSemanticBlock {
        override val plainText: String
            get() =
                (listOf(header) + rows)
                    .joinToString(separator = "\n") { row ->
                        row.joinToString(separator = " | ") { cell -> cell.plainText }
                    }
    }

    data class HtmlBlock(
        val literal: String,
    ) : MarkdownSemanticBlock {
        override val plainText: String
            get() = literal
    }
}

data class MarkdownSemanticListItem(
    val checked: Boolean?,
    val blocks: List<MarkdownSemanticBlock>,
) {
    val plainText: String
        get() = blocks.joinToString(separator = "\n") { it.plainText }
}

data class MarkdownSemanticTableCell(
    val inlines: List<MarkdownSemanticInline>,
) {
    val plainText: String
        get() = inlines.plainText()
}

sealed interface MarkdownSemanticInline {
    val plainText: String

    data class Text(
        override val plainText: String,
    ) : MarkdownSemanticInline

    data class Strong(
        val inlines: List<MarkdownSemanticInline>,
    ) : MarkdownSemanticInline {
        override val plainText: String
            get() = inlines.plainText()
    }

    data class Emphasis(
        val inlines: List<MarkdownSemanticInline>,
    ) : MarkdownSemanticInline {
        override val plainText: String
            get() = inlines.plainText()
    }

    data class Strikethrough(
        val inlines: List<MarkdownSemanticInline>,
    ) : MarkdownSemanticInline {
        override val plainText: String
            get() = inlines.plainText()
    }

    data class Code(
        override val plainText: String,
    ) : MarkdownSemanticInline

    data class Link(
        val destination: String,
        val title: String?,
        val inlines: List<MarkdownSemanticInline>,
    ) : MarkdownSemanticInline {
        override val plainText: String
            get() = inlines.plainText()
    }

    data class Image(
        val destination: String,
        val title: String?,
        val altText: String,
    ) : MarkdownSemanticInline {
        override val plainText: String
            get() = altText
    }

    data object SoftBreak : MarkdownSemanticInline {
        override val plainText: String = "\n"
    }

    data object HardBreak : MarkdownSemanticInline {
        override val plainText: String = "\n"
    }

    data class HtmlInline(
        override val plainText: String,
    ) : MarkdownSemanticInline
}

private val markdownSemanticParser by lazy {
    Parser
        .builder()
        .extensions(
            listOf(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                AutolinkExtension.create(),
                TaskListItemsExtension.create(),
            ),
        ).build()
}

fun parseMarkdownSemanticDocument(content: String): MarkdownSemanticDocument {
    val root = markdownSemanticParser.parse(content) as Document
    return MarkdownSemanticDocument(blocks = root.children().flatMap(::parseSemanticBlock))
}

private fun parseSemanticBlock(node: Node): List<MarkdownSemanticBlock> =
    when (node) {
        is Paragraph -> listOf(MarkdownSemanticBlock.Paragraph(node.parseSemanticInlines()))
        is Heading ->
            listOf(
                MarkdownSemanticBlock.Heading(
                    level = node.level,
                    inlines = node.parseSemanticInlines(),
                ),
            )
        is BlockQuote ->
            listOf(
                MarkdownSemanticBlock.BlockQuote(
                    blocks = node.children().flatMap(::parseSemanticBlock),
                ),
            )
        is BulletList ->
            listOf(
                MarkdownSemanticBlock.ListBlock(
                    ordered = false,
                    startNumber = 1,
                    items = node.children().filterIsInstance<ListItem>().map(::parseSemanticListItem),
                ),
            )
        is OrderedList ->
            listOf(
                MarkdownSemanticBlock.ListBlock(
                    ordered = true,
                    startNumber = node.markerStartNumber ?: 1,
                    items = node.children().filterIsInstance<ListItem>().map(::parseSemanticListItem),
                ),
            )
        is FencedCodeBlock ->
            listOf(
                MarkdownSemanticBlock.CodeBlock(
                    literal = node.literal.orEmpty().trimEnd('\n'),
                    language = node.info?.trim()?.takeIf { it.isNotEmpty() }?.substringBefore(' '),
                ),
            )
        is IndentedCodeBlock ->
            listOf(
                MarkdownSemanticBlock.CodeBlock(
                    literal = node.literal.orEmpty().trimEnd('\n'),
                    language = null,
                ),
            )
        is ThematicBreak -> listOf(MarkdownSemanticBlock.ThematicBreak)
        is TableBlock -> listOf(parseSemanticTable(node))
        is HtmlBlock -> listOf(MarkdownSemanticBlock.HtmlBlock(node.literal.orEmpty()))
        else -> node.children().flatMap(::parseSemanticBlock)
    }

private fun parseSemanticListItem(node: ListItem): MarkdownSemanticListItem =
    MarkdownSemanticListItem(
        checked = node.findFirstTaskListItemMarker()?.isChecked,
        blocks = node.children().flatMap(::parseSemanticBlock),
    )

private fun parseSemanticTable(node: TableBlock): MarkdownSemanticBlock.Table {
    val header =
        node.children()
            .filterIsInstance<TableHead>()
            .flatMap { head -> head.children().filterIsInstance<TableRow>() }
            .firstOrNull()
            ?.parseSemanticTableRow()
            .orEmpty()
    val rows =
        node.children()
            .filterIsInstance<TableBody>()
            .flatMap { body -> body.children().filterIsInstance<TableRow>() }
            .map { row -> row.parseSemanticTableRow() }
    return MarkdownSemanticBlock.Table(
        header = header,
        rows = rows,
    )
}

private fun TableRow.parseSemanticTableRow(): List<MarkdownSemanticTableCell> =
    children().filterIsInstance<TableCell>().map { cell ->
        MarkdownSemanticTableCell(inlines = cell.parseSemanticInlines())
    }

private fun Node.parseSemanticInlines(): List<MarkdownSemanticInline> =
    children().flatMap { child ->
        when (child) {
            is Text -> listOf(MarkdownSemanticInline.Text(child.literal.orEmpty()))
            is Emphasis -> listOf(MarkdownSemanticInline.Emphasis(child.parseSemanticInlines()))
            is StrongEmphasis -> listOf(MarkdownSemanticInline.Strong(child.parseSemanticInlines()))
            is CommonMarkStrikethrough -> listOf(MarkdownSemanticInline.Strikethrough(child.parseSemanticInlines()))
            is Code -> listOf(MarkdownSemanticInline.Code(child.literal.orEmpty()))
            is Link ->
                listOf(
                    MarkdownSemanticInline.Link(
                        destination = child.destination.orEmpty(),
                        title = child.title,
                        inlines = child.parseSemanticInlines(),
                    ),
                )
            is Image ->
                listOf(
                    MarkdownSemanticInline.Image(
                        destination = child.destination.orEmpty(),
                        title = child.title,
                        altText = child.parseSemanticInlines().plainText(),
                    ),
                )
            is SoftLineBreak -> listOf(MarkdownSemanticInline.SoftBreak)
            is HardLineBreak -> listOf(MarkdownSemanticInline.HardBreak)
            is HtmlInline -> listOf(MarkdownSemanticInline.HtmlInline(child.literal.orEmpty()))
            is TaskListItemMarker -> emptyList()
            is CustomNode -> child.parseSemanticInlines()
            else -> child.parseSemanticInlines()
        }
    }

private fun Node.findFirstTaskListItemMarker(): TaskListItemMarker? {
    if (this is TaskListItemMarker) return this
    children().forEach { child ->
        child.findFirstTaskListItemMarker()?.let { return it }
    }
    return null
}

private fun Node.children(): List<Node> {
    val result = mutableListOf<Node>()
    var child = firstChild
    while (child != null) {
        result += child
        child = child.next
    }
    return result
}

private fun List<MarkdownSemanticInline>.plainText(): String =
    joinToString(separator = "") { it.plainText }
