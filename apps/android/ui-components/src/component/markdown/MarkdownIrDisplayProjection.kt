package com.lomo.ui.component.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.lomo.domain.model.MediaClass
import com.lomo.domain.model.markdown.MarkdownRenderBlock
import com.lomo.domain.model.markdown.MarkdownRenderInline
import com.lomo.domain.model.markdown.MarkdownRenderListItem
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * Compose-ready projection of one render block: every derived string, span-styled text run,
 * image/text split, and list marker is resolved once when the presentation plan is built so the
 * renderer only dispatches — no IR walking happens inside composition.
 */
sealed interface MarkdownIrBlockDisplay {
    data class Paragraph(val text: AnnotatedString, val images: List<MarkdownPresentationImage>) :
        MarkdownIrBlockDisplay {
        val mediaGroups: List<ImmutableList<MarkdownPresentationImage>> = groupParagraphMedia(images)
    }

    data class Heading(val level: UInt, val text: AnnotatedString) : MarkdownIrBlockDisplay

    data class BlockQuote(val blocks: List<MarkdownIrBlockDisplay>) : MarkdownIrBlockDisplay

    data class ListBlock(val items: List<MarkdownIrListItemDisplay>) : MarkdownIrBlockDisplay

    data class CodeBlock(val literal: String) : MarkdownIrBlockDisplay

    data object ThematicBreak : MarkdownIrBlockDisplay

    /** Rows are pre-joined display lines (header first); cell styling is not rendered today. */
    data class Table(val rows: List<String>) : MarkdownIrBlockDisplay

    data class HtmlBlock(val literal: String) : MarkdownIrBlockDisplay
}

/** Text is rendered separately; adjacent pictures share a pager, audio retains its own player. */
private fun groupParagraphMedia(
    images: List<MarkdownPresentationImage>,
): List<ImmutableList<MarkdownPresentationImage>> = buildList {
    val pending = mutableListOf<MarkdownPresentationImage>()
    fun flush() {
        if (pending.isNotEmpty()) {
            add(pending.toImmutableList())
            pending.clear()
        }
    }
    images.forEach { image ->
        if (image.mediaClass == MediaClass.AUDIO) {
            flush()
            add(listOf(image).toImmutableList())
        } else {
            pending += image
        }
    }
    flush()
}

data class MarkdownIrListItemDisplay(
    val actionSpan: MarkdownSourceSpan?,
    val checked: Boolean?,
    val marker: String,
    val blocks: List<MarkdownIrBlockDisplay>,
)

internal fun buildMarkdownIrBlockDisplay(
    block: MarkdownRenderBlock,
    completedTask: Boolean = false,
): MarkdownIrBlockDisplay = when (block) {
    is MarkdownRenderBlock.Paragraph -> {
        val projection = projectParagraphMedia(block.inlines)
        MarkdownIrBlockDisplay.Paragraph(
            text = projection.text.withTaskCompletion(completedTask),
            images = projection.images,
        )
    }

    is MarkdownRenderBlock.Heading ->
        MarkdownIrBlockDisplay.Heading(
            level = block.level,
            text = block.inlines.toAnnotatedText().withTaskCompletion(completedTask),
        )

    is MarkdownRenderBlock.BlockQuote ->
        MarkdownIrBlockDisplay.BlockQuote(
            blocks = block.blocks.flatMap(::splitAudioParagraph).map {
                buildMarkdownIrBlockDisplay(it, completedTask)
            },
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

private fun buildMarkdownIrListItemDisplay(item: MarkdownRenderListItem, marker: String): MarkdownIrListItemDisplay =
    MarkdownIrListItemDisplay(
        actionSpan = item.actionSpan,
        checked = item.checked,
        marker = marker,
        blocks = item.blocks.flatMap(::splitAudioParagraph).map {
            buildMarkdownIrBlockDisplay(it, completedTask = item.checked == true)
        },
    )

internal fun MarkdownRenderInline.Image.toPresentationImage(): MarkdownPresentationImage = MarkdownPresentationImage(
    destination = destination,
    description = title ?: altText.takeIf(String::isNotBlank),
    mediaClass = mediaClass,
)

internal fun List<MarkdownRenderInline>.toPlainText(): String =
    buildString { this@toPlainText.forEach { inline -> append(inline.plainText()) } }

private fun MarkdownRenderInline.plainText(): String = when (this) {
    is MarkdownRenderInline.Text -> text

    is MarkdownRenderInline.Strong -> inlines.toPlainText()

    is MarkdownRenderInline.Emphasis -> inlines.toPlainText()

    is MarkdownRenderInline.Strikethrough -> inlines.toPlainText()

    is MarkdownRenderInline.Underline -> inlines.toPlainText()

    is MarkdownRenderInline.Highlight -> inlines.toPlainText()

    is MarkdownRenderInline.Code -> text

    is MarkdownRenderInline.Link -> inlines.toPlainText().ifBlank { destination }

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

        is MarkdownRenderInline.Underline ->
            withStyle(SpanStyle(textDecoration = TextDecoration.Underline), inline.inlines)

        is MarkdownRenderInline.Highlight -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold), inline.inlines)

        is MarkdownRenderInline.Code -> append(inline.text)

        is MarkdownRenderInline.Link -> {
            pushLink(LinkAnnotation.Url(inline.destination))
            if (inline.inlines.toPlainText().isBlank()) {
                append(inline.destination)
            } else {
                inline.inlines.forEach(::appendInline)
            }
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

private fun AnnotatedString.Builder.withStyle(style: SpanStyle, children: List<MarkdownRenderInline>) {
    pushStyle(style)
    children.forEach(::appendInline)
    pop()
}

/** Completion adds a decoration while retaining links, emphasis and existing decorations. */
private fun AnnotatedString.withTaskCompletion(completed: Boolean): AnnotatedString {
    if (!completed || isEmpty()) return this
    return AnnotatedString.Builder(this).apply {
        addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), 0, length)
        this@withTaskCompletion.spanStyles.forEach { range ->
            val decoration = range.item.textDecoration
            if (decoration != null) {
                addStyle(
                    range.item.copy(
                        textDecoration = TextDecoration.combine(listOf(decoration, TextDecoration.LineThrough)),
                    ),
                    range.start,
                    range.end,
                )
            }
        }
    }.toAnnotatedString()
}
