package com.lomo.app.util

import com.lomo.domain.model.MediaFileExtensions
import com.lomo.ui.component.markdown.MarkdownSemanticBlock
import com.lomo.ui.component.markdown.MarkdownSemanticInline
import com.lomo.ui.component.markdown.MarkdownSemanticListItem
import com.lomo.ui.component.markdown.parseMarkdownSemanticDocument
import com.lomo.ui.text.normalizeCjkMixedSpacingForDisplay

internal fun buildMarkdownShareBodyLines(
    bodyText: String,
    imagePlaceholder: String,
    audioPlaceholder: String = "[Audio]",
): List<ShareBodyLine> {
    if (bodyText.isBlank()) {
        return listOf(defaultMarkdownParagraphLine())
    }

    val document = parseMarkdownSemanticDocument(bodyText)
    val lines =
        document.blocks
            .flatMap { block ->
                block.toShareBodyLines(
                    imagePlaceholder = imagePlaceholder,
                    audioPlaceholder = audioPlaceholder,
                    quoteDepth = 0,
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

private fun MarkdownSemanticBlock.toShareBodyLines(
    imagePlaceholder: String,
    audioPlaceholder: String,
    quoteDepth: Int,
): List<ShareBodyLine> =
    when (this) {
        is MarkdownSemanticBlock.Heading ->
            listOf(
                ShareBodyLine(
                    text = inlines.toStyledShareText(imagePlaceholder).text.normalizeCjkMixedSpacingForDisplay(),
                    type = if (quoteDepth > 0) ShareBodyLineType.Quote else ShareBodyLineType.Heading,
                    headingLevel = level,
                    inlineStyles = inlines.toStyledShareText(imagePlaceholder).styles,
                ),
            )
        is MarkdownSemanticBlock.Paragraph ->
            paragraphToShareBodyLines(
                inlines = inlines,
                imagePlaceholder = imagePlaceholder,
                audioPlaceholder = audioPlaceholder,
                quoteDepth = quoteDepth,
            )
        is MarkdownSemanticBlock.BlockQuote ->
            blocks.flatMap { block ->
                block.toShareBodyLines(
                    imagePlaceholder = imagePlaceholder,
                    audioPlaceholder = audioPlaceholder,
                    quoteDepth = quoteDepth + 1,
                )
            }.map { line -> line.withQuoteMarker() }
        is MarkdownSemanticBlock.ListBlock ->
            items.flatMapIndexed { index, item ->
                item.toShareBodyLines(
                    marker = resolveListMarker(index),
                    imagePlaceholder = imagePlaceholder,
                    audioPlaceholder = audioPlaceholder,
                    quoteDepth = quoteDepth,
                )
            }
        is MarkdownSemanticBlock.CodeBlock ->
            literal
                .lineSequence()
                .filter { it.isNotBlank() }
                .map { line -> ShareBodyLine(line, ShareBodyLineType.Code) }
                .toList()
        is MarkdownSemanticBlock.Table -> toShareBodyTableLines()
        is MarkdownSemanticBlock.ThematicBreak -> emptyList()
        is MarkdownSemanticBlock.HtmlBlock -> {
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

private fun MarkdownSemanticBlock.ListBlock.resolveListMarker(index: Int): String =
    if (ordered) {
        "${startNumber + index}. "
    } else {
        BULLET_PREFIX
    }

private fun MarkdownSemanticListItem.toShareBodyLines(
    marker: String,
    imagePlaceholder: String,
    audioPlaceholder: String,
    quoteDepth: Int,
): List<ShareBodyLine> {
    val itemText = blocks.joinToString(separator = " ") { it.plainText }.trim()
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
    inlines: List<MarkdownSemanticInline>,
    imagePlaceholder: String,
    audioPlaceholder: String,
    quoteDepth: Int,
): List<ShareBodyLine> {
    val singleInline = inlines.singleOrNull()
    if (singleInline is MarkdownSemanticInline.Image) {
        return listOf(singleInline.toImageOrAudioLine(audioPlaceholder))
    }

    val styledText = inlines.toStyledShareText(imagePlaceholder)
    val markerLine = styledText.text.trim().takeIf { it.isNotEmpty() }?.toImageMarkerLineOrNull()
    if (markerLine != null) return listOf(markerLine)

    val text = IMAGE_MARKER_PATTERN.replace(styledText.text, imagePlaceholder).trim()
    if (text.isBlank()) return emptyList()

    return listOf(
        ShareBodyLine(
            text = text.normalizeCjkMixedSpacingForDisplay(),
            type = if (quoteDepth > 0) ShareBodyLineType.Quote else ShareBodyLineType.Paragraph,
            inlineStyles = styledText.styles,
        ),
    )
}

private fun MarkdownSemanticInline.Image.toImageOrAudioLine(audioPlaceholder: String): ShareBodyLine =
    if (MediaFileExtensions.hasAudioExtension(destination)) {
        ShareBodyLine(audioPlaceholder, ShareBodyLineType.Paragraph)
    } else {
        ShareBodyLine(BLANK_LAYOUT_TEXT, ShareBodyLineType.Image, imageIndex = NO_IMAGE_INDEX)
    }

private fun String.toImageMarkerLineOrNull(): ShareBodyLine? {
    val match = IMAGE_MARKER_PATTERN.find(this) ?: return null
    if (match.value != this) return null
    val imageIndex =
        match
            .groupValues
            .getOrNull(IMAGE_MARKER_INDEX_GROUP)
            ?.toIntOrNull()
            ?: NO_IMAGE_INDEX
    return ShareBodyLine(text = this, type = ShareBodyLineType.Image, imageIndex = imageIndex)
}

private fun MarkdownSemanticBlock.Table.toShareBodyTableLines(): List<ShareBodyLine> =
    (listOf(header) + rows)
        .filter { row -> row.isNotEmpty() }
        .map { row ->
            ShareBodyLine(
                text = row.joinToString(separator = " | ") { cell -> cell.toStyledShareText().text },
                type = ShareBodyLineType.Table,
            )
        }
