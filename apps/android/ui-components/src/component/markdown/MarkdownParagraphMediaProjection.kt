package com.lomo.ui.component.markdown

import androidx.compose.ui.text.AnnotatedString
import com.lomo.domain.model.markdown.MarkdownRenderInline

internal data class MarkdownParagraphMediaProjection(
    val text: AnnotatedString,
    val images: List<MarkdownPresentationImage>,
)

/** Split typed media from text before layout; only lines emptied by extraction disappear. */
internal fun projectParagraphMedia(inlines: List<MarkdownRenderInline>): MarkdownParagraphMediaProjection {
    val images = mutableListOf<MarkdownPresentationImage>()
    val lines = mutableListOf<Pair<List<MarkdownRenderInline>, MarkdownRenderInline?>>()
    val line = mutableListOf<MarkdownRenderInline>()
    var lineHadMedia = false
    fun flush(terminator: MarkdownRenderInline?) {
        if (!lineHadMedia || line.toPlainText().isNotBlank()) {
            lines += line.toList() to terminator
        }
        line.clear()
        lineHadMedia = false
    }
    fun visit(inline: MarkdownRenderInline, wrap: (MarkdownRenderInline) -> MarkdownRenderInline) {
        fun children(items: List<MarkdownRenderInline>, wrapChild: (MarkdownRenderInline) -> MarkdownRenderInline) {
            if (items.isEmpty()) {
                line += wrap(inline)
            } else {
                items.forEach { child -> visit(child) { wrap(wrapChild(it)) } }
            }
        }
        when (inline) {
            is MarkdownRenderInline.Image -> {
                images += inline.toPresentationImage()
                lineHadMedia = true
            }

            is MarkdownRenderInline.SoftBreak, is MarkdownRenderInline.HardBreak -> flush(inline)

            is MarkdownRenderInline.Strong -> children(inline.inlines) { inline.copy(inlines = listOf(it)) }

            is MarkdownRenderInline.Emphasis -> children(inline.inlines) { inline.copy(inlines = listOf(it)) }

            is MarkdownRenderInline.Strikethrough -> children(inline.inlines) { inline.copy(inlines = listOf(it)) }

            is MarkdownRenderInline.Underline -> children(inline.inlines) { inline.copy(inlines = listOf(it)) }

            is MarkdownRenderInline.Highlight -> children(inline.inlines) { inline.copy(inlines = listOf(it)) }

            is MarkdownRenderInline.Link -> children(inline.inlines) { inline.copy(inlines = listOf(it)) }

            is MarkdownRenderInline.WikiReference -> children(inline.inlines) { inline.copy(inlines = listOf(it)) }

            else -> line += wrap(inline)
        }
    }
    inlines.forEach { visit(it) { leaf -> leaf } }
    flush(null)
    val text = buildList {
        lines.forEachIndexed { index, (parts, terminator) ->
            addAll(parts)
            if (index < lines.lastIndex) {
                add(requireNotNull(terminator) { "Nonterminal paragraph line requires a break" })
            }
        }
    }.toAnnotatedText()
    return MarkdownParagraphMediaProjection(text, images)
}
