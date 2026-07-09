package com.lomo.ui.component.markdown

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

    data class Highlight(
        val inlines: List<MarkdownSemanticInline>,
    ) : MarkdownSemanticInline {
        override val plainText: String
            get() = inlines.plainText()
    }
}

internal data class MarkdownLinkReference(
    val destination: String,
    val title: String?,
)

fun parseMarkdownSemanticDocument(content: String): MarkdownSemanticDocument {
    val root = parseModernMarkdownDocument(content)
    val references = parseMarkdownLinkReferences(root, content)
    val parsedBlocks = parseMarkdownSemanticBlocks(root.children, content, references)
    return MarkdownSemanticDocument(
        blocks = parsedBlocks.map { it.parseHighlights() },
    )
}

internal fun List<MarkdownSemanticInline>.parseHighlights(): List<MarkdownSemanticInline> {
    val splitInlines = flatMap { inline ->
        when (inline) {
            is MarkdownSemanticInline.Text -> {
                val text = inline.plainText
                if (text.contains("==")) {
                    val parts = mutableListOf<MarkdownSemanticInline>()
                    var start = 0
                    var idx = text.indexOf("==")
                    while (idx != -1) {
                        if (idx > start) {
                            parts.add(MarkdownSemanticInline.Text(text.substring(start, idx)))
                        }
                        parts.add(MarkdownSemanticInline.Text("=="))
                        start = idx + 2
                        idx = text.indexOf("==", start)
                    }
                    if (start < text.length) {
                        parts.add(MarkdownSemanticInline.Text(text.substring(start)))
                    }
                    parts
                } else {
                    listOf(inline)
                }
            }
            is MarkdownSemanticInline.Strong -> listOf(inline.copy(inlines = inline.inlines.parseHighlights()))
            is MarkdownSemanticInline.Emphasis -> listOf(inline.copy(inlines = inline.inlines.parseHighlights()))
            is MarkdownSemanticInline.Strikethrough -> listOf(inline.copy(inlines = inline.inlines.parseHighlights()))
            is MarkdownSemanticInline.Link -> listOf(inline.copy(inlines = inline.inlines.parseHighlights()))
            is MarkdownSemanticInline.Highlight -> listOf(inline.copy(inlines = inline.inlines.parseHighlights()))
            else -> listOf(inline)
        }
    }

    val result = mutableListOf<MarkdownSemanticInline>()
    var i = 0
    while (i < splitInlines.size) {
        val current = splitInlines[i]
        if (current is MarkdownSemanticInline.Text && current.plainText == "==") {
            var j = i + 1
            var matchIdx = -1
            while (j < splitInlines.size) {
                val candidate = splitInlines[j]
                if (candidate is MarkdownSemanticInline.Text && candidate.plainText == "==") {
                    matchIdx = j
                    break
                }
                j++
            }
            if (matchIdx != -1) {
                val subList = splitInlines.subList(i + 1, matchIdx)
                result.add(MarkdownSemanticInline.Highlight(subList.parseHighlights()))
                i = matchIdx + 1
            } else {
                result.add(MarkdownSemanticInline.Text("=="))
                i++
            }
        } else {
            result.add(current)
            i++
        }
    }
    return result
}

internal fun MarkdownSemanticBlock.parseHighlights(): MarkdownSemanticBlock =
    when (this) {
        is MarkdownSemanticBlock.Paragraph -> copy(inlines = inlines.parseHighlights())
        is MarkdownSemanticBlock.Heading -> copy(inlines = inlines.parseHighlights())
        is MarkdownSemanticBlock.BlockQuote -> copy(blocks = blocks.map { it.parseHighlights() })
        is MarkdownSemanticBlock.ListBlock -> copy(items = items.map { item ->
            item.copy(blocks = item.blocks.map { it.parseHighlights() })
        })
        is MarkdownSemanticBlock.Table -> copy(
            header = header.map { cell -> cell.copy(inlines = cell.inlines.parseHighlights()) },
            rows = rows.map { row ->
                row.map { cell -> cell.copy(inlines = cell.inlines.parseHighlights()) }
            }
        )
        else -> this
    }

internal fun List<MarkdownSemanticInline>.plainText(): String =
    joinToString(separator = "") { inline -> inline.plainText }

internal fun normalizeReferenceLabel(label: String): String =
    label.trim().replace(Regex("\\s+"), " ").lowercase()
