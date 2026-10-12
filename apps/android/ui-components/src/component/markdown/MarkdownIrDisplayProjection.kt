package com.lomo.ui.component.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.lomo.domain.model.markdown.MarkdownRenderBlock
import com.lomo.domain.model.markdown.MarkdownRenderInline
import com.lomo.domain.model.markdown.MarkdownRenderListItem
import com.lomo.domain.model.markdown.MarkdownSourceSpan

/**
 * Compose-ready projection of one render block: every derived string, span-styled text run,
 * image/text split, and list marker is resolved once when the presentation plan is built so the
 * renderer only dispatches — no IR walking happens inside composition.
 */
sealed interface MarkdownIrBlockDisplay {
    data class Paragraph(
        val text: AnnotatedString,
        val images: List<MarkdownPresentationImage>,
    ) : MarkdownIrBlockDisplay

    data class Heading(
        val level: UInt,
        val text: AnnotatedString,
    ) : MarkdownIrBlockDisplay

    data class BlockQuote(
        val blocks: List<MarkdownIrBlockDisplay>,
    ) : MarkdownIrBlockDisplay

    data class ListBlock(
        val items: List<MarkdownIrListItemDisplay>,
    ) : MarkdownIrBlockDisplay

    data class CodeBlock(
        val literal: String,
    ) : MarkdownIrBlockDisplay

    data object ThematicBreak : MarkdownIrBlockDisplay

    /** Rows are pre-joined display lines (header first); cell styling is not rendered today. */
    data class Table(
        val rows: List<String>,
    ) : MarkdownIrBlockDisplay

    data class HtmlBlock(
        val literal: String,
    ) : MarkdownIrBlockDisplay
}

data class MarkdownIrListItemDisplay(
    val actionSpan: MarkdownSourceSpan?,
    val checked: Boolean?,
    val marker: String,
    val blocks: List<MarkdownIrBlockDisplay>,
)

internal fun buildMarkdownIrBlockDisplay(block: MarkdownRenderBlock): MarkdownIrBlockDisplay =
    when (block) {
        is MarkdownRenderBlock.Paragraph -> {
            val images =
                block.inlines
                    .filterIsInstance<MarkdownRenderInline.Image>()
                    .map(MarkdownRenderInline.Image::toPresentationImage)
            val text =
                block.inlines
                    .filterNot { inline -> inline is MarkdownRenderInline.Image }
                    .toAnnotatedText()
            MarkdownIrBlockDisplay.Paragraph(text = text, images = images)
        }
        is MarkdownRenderBlock.Heading ->
            MarkdownIrBlockDisplay.Heading(
                level = block.level,
                text = block.inlines.toAnnotatedText(),
            )
        is MarkdownRenderBlock.BlockQuote ->
            MarkdownIrBlockDisplay.BlockQuote(
                blocks = block.blocks.map(::buildMarkdownIrBlockDisplay),
            )
        is MarkdownRenderBlock.ListBlock ->
            MarkdownIrBlockDisplay.ListBlock(
                items =
                    block.items.mapIndexed { index, item ->
                        buildMarkdownIrListItemDisplay(
                            item = item,
                            marker =
                                if (block.ordered) {
                                    "${block.startNumber + index.toULong()}."
                                } else {
                                    "•"
                                },
                        )
                    },
            )
        is MarkdownRenderBlock.CodeBlock -> MarkdownIrBlockDisplay.CodeBlock(literal = block.literal)
        is MarkdownRenderBlock.ThematicBreak -> MarkdownIrBlockDisplay.ThematicBreak
        is MarkdownRenderBlock.Table ->
            MarkdownIrBlockDisplay.Table(
                rows =
                    (listOf(block.header) + block.rows).map { row ->
                        row.joinToString(" | ") { cell -> cell.inlines.toPlainText() }
                    },
            )
        is MarkdownRenderBlock.HtmlBlock -> MarkdownIrBlockDisplay.HtmlBlock(literal = block.literal)
    }

private fun buildMarkdownIrListItemDisplay(
    item: MarkdownRenderListItem,
    marker: String,
): MarkdownIrListItemDisplay =
    MarkdownIrListItemDisplay(
        actionSpan = item.actionSpan,
        checked = item.checked,
        marker = marker,
        blocks = item.blocks.map(::buildMarkdownIrBlockDisplay),
    )

internal fun MarkdownRenderInline.Image.toPresentationImage(): MarkdownPresentationImage =
    MarkdownPresentationImage(
        destination = destination,
        description = title ?: altText.takeIf(String::isNotBlank),
        mediaClass = mediaClass,
    )

internal fun List<MarkdownRenderInline>.toPlainText(): String =
    buildString { this@toPlainText.forEach { inline -> append(inline.plainText()) } }

private fun MarkdownRenderInline.plainText(): String =
    when (this) {
        is MarkdownRenderInline.Text -> text
        is MarkdownRenderInline.Strong -> inlines.toPlainText()
        is MarkdownRenderInline.Emphasis -> inlines.toPlainText()
        is MarkdownRenderInline.Strikethrough -> inlines.toPlainText()
        is MarkdownRenderInline.Highlight -> inlines.toPlainText()
        is MarkdownRenderInline.Code -> text
        is MarkdownRenderInline.Link -> inlines.toPlainText()
        is MarkdownRenderInline.Image -> altText
        is MarkdownRenderInline.Tag -> "#$name"
        is MarkdownRenderInline.Reminder -> token
        is MarkdownRenderInline.WikiReference -> inlines.toPlainText().ifBlank { target }
        is MarkdownRenderInline.SoftBreak,
        is MarkdownRenderInline.HardBreak,
        -> "\n"
        is MarkdownRenderInline.HtmlInline -> literal
    }

internal fun List<MarkdownRenderInline>.toAnnotatedText(): AnnotatedString {
    val builder = AnnotatedString.Builder()
    forEach { inline -> builder.appendInline(inline) }
    return builder.toAnnotatedString()
}

private fun AnnotatedString.Builder.appendInline(inline: MarkdownRenderInline) {
    when (inline) {
        is MarkdownRenderInline.Text -> append(inline.text)
        is MarkdownRenderInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.Bold), inline.inlines)
        is MarkdownRenderInline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic), inline.inlines)
        is MarkdownRenderInline.Strikethrough ->
            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), inline.inlines)
        is MarkdownRenderInline.Highlight -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold), inline.inlines)
        is MarkdownRenderInline.Code -> append(inline.text)
        is MarkdownRenderInline.Link -> {
            pushLink(LinkAnnotation.Url(inline.destination))
            inline.inlines.forEach(::appendInline)
            pop()
        }
        is MarkdownRenderInline.Image -> Unit
        is MarkdownRenderInline.Tag -> append("#${inline.name}")
        is MarkdownRenderInline.Reminder -> append(inline.token)
        is MarkdownRenderInline.WikiReference ->
            if (inline.inlines.isEmpty()) append(inline.target) else inline.inlines.forEach(::appendInline)
        is MarkdownRenderInline.SoftBreak,
        is MarkdownRenderInline.HardBreak,
        -> append('\n')
        is MarkdownRenderInline.HtmlInline -> append(inline.literal)
    }
}

private fun AnnotatedString.Builder.withStyle(
    style: SpanStyle,
    children: List<MarkdownRenderInline>,
) {
    pushStyle(style)
    children.forEach(::appendInline)
    pop()
}
