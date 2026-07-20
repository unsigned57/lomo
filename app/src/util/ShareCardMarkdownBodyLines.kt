package com.lomo.app.util

import com.lomo.domain.model.MediaFileExtensions
import com.lomo.domain.model.markdown.MarkdownRenderBlock
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownRenderInline
import com.lomo.domain.model.markdown.MarkdownRenderListItem
import com.lomo.ui.text.normalizeCjkMixedSpacingForDisplay

internal fun buildMarkdownShareBodyLines(
    document: MarkdownRenderDocument,
    imagePlaceholder: String,
    audioPlaceholder: String = "[Audio]",
): List<ShareBodyLine> {
    if (document.plainText.isBlank() && document.blocks.isEmpty()) {
        return listOf(defaultMarkdownParagraphLine())
    }

    val imageIndex = intArrayOf(0)
    val lines =
        document.blocks
            .flatMap { block ->
                block.toShareBodyLines(
                    imagePlaceholder = imagePlaceholder,
                    audioPlaceholder = audioPlaceholder,
                    quoteDepth = 0,
                    nextImageIndex = { imageIndex[0]++ },
                )
            }.take(MAX_SHARE_BODY_LINES)

    return lines.ifEmpty { listOf(defaultMarkdownParagraphLine()) }
}

internal fun shareBodyLinesTextLengthWithoutMarkers(lines: List<ShareBodyLine>): Int =
    lines.sumOf { line ->
        if (line.type == ShareBodyLineType.Image) {
            0
        } else {
            line.text.length
        }
    }

private fun defaultMarkdownParagraphLine(): ShareBodyLine =
    ShareBodyLine(BLANK_LAYOUT_TEXT, ShareBodyLineType.Paragraph)

private fun MarkdownRenderBlock.toShareBodyLines(
    imagePlaceholder: String,
    audioPlaceholder: String,
    quoteDepth: Int,
    nextImageIndex: () -> Int,
): List<ShareBodyLine> =
    when (this) {
        is MarkdownRenderBlock.Heading ->
            listOf(
                ShareBodyLine(
                    text = inlines.toStyledShareText(imagePlaceholder).text.normalizeCjkMixedSpacingForDisplay(),
                    type = if (quoteDepth > 0) ShareBodyLineType.Quote else ShareBodyLineType.Heading,
                    headingLevel = level.toInt(),
                    inlineStyles = inlines.toStyledShareText(imagePlaceholder).styles,
                ),
            )
        is MarkdownRenderBlock.Paragraph ->
            paragraphToShareBodyLines(
                inlines = inlines,
                imagePlaceholder = imagePlaceholder,
                audioPlaceholder = audioPlaceholder,
                quoteDepth = quoteDepth,
                nextImageIndex = nextImageIndex,
            )
        is MarkdownRenderBlock.BlockQuote ->
            blocks.flatMap { block ->
                block.toShareBodyLines(
                    imagePlaceholder = imagePlaceholder,
                    audioPlaceholder = audioPlaceholder,
                    quoteDepth = quoteDepth + 1,
                    nextImageIndex = nextImageIndex,
                )
            }.map { line -> line.withQuoteMarker() }
        is MarkdownRenderBlock.ListBlock ->
            items.flatMapIndexed { index, item ->
                item.toShareBodyLines(
                    marker = resolveListMarker(index),
                    imagePlaceholder = imagePlaceholder,
                    audioPlaceholder = audioPlaceholder,
                    quoteDepth = quoteDepth,
                    nextImageIndex = nextImageIndex,
                )
            }
        is MarkdownRenderBlock.CodeBlock ->
            literal
                .lineSequence()
                .filter { it.isNotBlank() }
                .map { line -> ShareBodyLine(line, ShareBodyLineType.Code) }
                .toList()
        is MarkdownRenderBlock.Table -> toShareBodyTableLines()
        is MarkdownRenderBlock.ThematicBreak -> emptyList()
        is MarkdownRenderBlock.HtmlBlock -> {
            val styledText = literal.toStyledShareTextFromHtmlFragment(imagePlaceholder)
            val text = styledText.text.trim()
            if (text.isBlank()) {
                emptyList()
            } else {
                listOf(
                    ShareBodyLine(
                        text = text.normalizeCjkMixedSpacingForDisplay(),
                        type = if (quoteDepth > 0) ShareBodyLineType.Quote else ShareBodyLineType.Paragraph,
                        inlineStyles = styledText.styles,
                    ),
                )
            }
        }
    }

private fun MarkdownRenderBlock.ListBlock.resolveListMarker(index: Int): String =
    if (ordered) {
        "${startNumber + index.toULong()}. "
    } else {
        BULLET_PREFIX
    }

private fun MarkdownRenderListItem.toShareBodyLines(
    marker: String,
    imagePlaceholder: String,
    audioPlaceholder: String,
    quoteDepth: Int,
    nextImageIndex: () -> Int,
): List<ShareBodyLine> {
    val itemText = blocks.joinToString(separator = " ") { it.toPlainText() }.trim()
    val resolvedMarker =
        when (checked) {
            true -> CHECKED_TODO_PREFIX
            false -> UNCHECKED_TODO_PREFIX
            null -> marker
        }
    val prefix =
        if (resolvedMarker == CHECKED_TODO_PREFIX || resolvedMarker == UNCHECKED_TODO_PREFIX) {
            "$resolvedMarker "
        } else {
            resolvedMarker
        }
    val childLines =
        blocks.flatMap { block ->
            block.toShareBodyLines(
                imagePlaceholder = imagePlaceholder,
                audioPlaceholder = audioPlaceholder,
                quoteDepth = quoteDepth,
                nextImageIndex = nextImageIndex,
            )
        }

    return if (childLines.isEmpty()) {
        listOf(ShareBodyLine(prefix.trim(), ShareBodyLineType.Bullet))
    } else {
        childLines.mapIndexed { index, line ->
            if (index == 0 && line.type != ShareBodyLineType.Image) {
                line.copy(
                    text = "$prefix${line.text}",
                    type = ShareBodyLineType.Bullet,
                    inlineStyles = line.inlineStyles.shift(prefix.length),
                )
            } else if (line.text == itemText) {
                line.copy(type = ShareBodyLineType.Bullet)
            } else {
                line
            }
        }
    }
}

private fun paragraphToShareBodyLines(
    inlines: List<MarkdownRenderInline>,
    imagePlaceholder: String,
    audioPlaceholder: String,
    quoteDepth: Int,
    nextImageIndex: () -> Int,
): List<ShareBodyLine> {
    val singleInline = inlines.singleOrNull()
    if (singleInline is MarkdownRenderInline.Image) {
        return listOf(singleInline.toImageOrAudioLine(audioPlaceholder, nextImageIndex))
    }

    // Mixed paragraph: emit standalone image lines for IR Image nodes, then residual text.
    val imageOnly =
        inlines.filterIsInstance<MarkdownRenderInline.Image>().map { image ->
            image.toImageOrAudioLine(audioPlaceholder, nextImageIndex)
        }
    if (imageOnly.isNotEmpty() && inlines.all { it is MarkdownRenderInline.Image }) {
        return imageOnly
    }

    val styledText = inlines.toStyledShareText(imagePlaceholder)
    val text = IMAGE_MARKER_PATTERN.replace(styledText.text, imagePlaceholder).trim()
    if (text.isBlank()) {
        return imageOnly.ifEmpty { emptyList() }
    }

    return imageOnly +
        listOf(
            ShareBodyLine(
                text = text.normalizeCjkMixedSpacingForDisplay(),
                type = if (quoteDepth > 0) ShareBodyLineType.Quote else ShareBodyLineType.Paragraph,
                inlineStyles = styledText.styles,
            ),
        )
}

private fun MarkdownRenderInline.Image.toImageOrAudioLine(
    audioPlaceholder: String,
    nextImageIndex: () -> Int,
): ShareBodyLine =
    if (MediaFileExtensions.hasAudioExtension(destination)) {
        ShareBodyLine(audioPlaceholder, ShareBodyLineType.Paragraph)
    } else {
        ShareBodyLine(BLANK_LAYOUT_TEXT, ShareBodyLineType.Image, imageIndex = nextImageIndex())
    }

private fun MarkdownRenderBlock.Table.toShareBodyTableLines(): List<ShareBodyLine> =
    (listOf(header) + rows)
        .filter { row -> row.isNotEmpty() }
        .map { row ->
            ShareBodyLine(
                text = row.joinToString(separator = " | ") { cell -> cell.toStyledShareText().text },
                type = ShareBodyLineType.Table,
            )
        }

private fun MarkdownRenderBlock.toPlainText(): String =
    when (this) {
        is MarkdownRenderBlock.Paragraph -> inlines.toPlainText()
        is MarkdownRenderBlock.Heading -> inlines.toPlainText()
        is MarkdownRenderBlock.BlockQuote -> blocks.joinToString(" ") { it.toPlainText() }
        is MarkdownRenderBlock.ListBlock -> items.joinToString(" ") { item -> item.blocks.joinToString(" ") { it.toPlainText() } }
        is MarkdownRenderBlock.CodeBlock -> literal
        is MarkdownRenderBlock.ThematicBreak -> ""
        is MarkdownRenderBlock.Table ->
            (listOf(header) + rows).joinToString(" ") { row -> row.joinToString(" ") { it.inlines.toPlainText() } }
        is MarkdownRenderBlock.HtmlBlock -> literal
    }

private fun List<MarkdownRenderInline>.toPlainText(): String =
    joinToString(separator = "") { inline ->
        when (inline) {
            is MarkdownRenderInline.Text -> inline.text
            is MarkdownRenderInline.Strong -> inline.inlines.toPlainText()
            is MarkdownRenderInline.Emphasis -> inline.inlines.toPlainText()
            is MarkdownRenderInline.Strikethrough -> inline.inlines.toPlainText()
            is MarkdownRenderInline.Highlight -> inline.inlines.toPlainText()
            is MarkdownRenderInline.Code -> inline.text
            is MarkdownRenderInline.Link -> inline.inlines.toPlainText()
            is MarkdownRenderInline.Image -> inline.altText
            is MarkdownRenderInline.Tag -> "#${inline.name}"
            is MarkdownRenderInline.Reminder -> inline.token
            is MarkdownRenderInline.WikiReference -> inline.inlines.toPlainText().ifBlank { inline.target }
            is MarkdownRenderInline.SoftBreak,
            is MarkdownRenderInline.HardBreak,
            -> "\n"
            is MarkdownRenderInline.HtmlInline -> inline.literal
        }
    }
