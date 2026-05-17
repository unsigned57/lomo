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
}

internal data class MarkdownLinkReference(
    val destination: String,
    val title: String?,
)

fun parseMarkdownSemanticDocument(content: String): MarkdownSemanticDocument {
    val root = parseModernMarkdownDocument(content)
    val references = parseMarkdownLinkReferences(root, content)
    return MarkdownSemanticDocument(
        blocks = parseMarkdownSemanticBlocks(root.children, content, references),
    )
}

internal fun List<MarkdownSemanticInline>.plainText(): String =
    joinToString(separator = "") { inline -> inline.plainText }

internal fun normalizeReferenceLabel(label: String): String =
    label.trim().replace(Regex("\\s+"), " ").lowercase()
