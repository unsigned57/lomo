package com.lomo.app.util

import com.lomo.domain.model.MediaFileExtensions
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownRenderInline
import com.lomo.ui.text.normalizeCjkMixedSpacingForDisplay

/**
 * Counts non-audio image slots from the owner render IR (same parse as body lines).
 * Does not re-parse Markdown image syntax in Kotlin.
 */
internal fun countShareCardImageSlots(document: MarkdownRenderDocument): Int {
    var count = 0
    fun walkInlines(inlines: List<MarkdownRenderInline>) {
        inlines.forEach { inline ->
            when (inline) {
                is MarkdownRenderInline.Image -> {
                    if (!MediaFileExtensions.hasAudioExtension(inline.destination)) {
                        count++
                    }
                }
                is MarkdownRenderInline.Link -> walkInlines(inline.inlines)
                is MarkdownRenderInline.Strong -> walkInlines(inline.inlines)
                is MarkdownRenderInline.Emphasis -> walkInlines(inline.inlines)
                is MarkdownRenderInline.Strikethrough -> walkInlines(inline.inlines)
                is MarkdownRenderInline.Highlight -> walkInlines(inline.inlines)
                is MarkdownRenderInline.WikiReference -> walkInlines(inline.inlines)
                else -> Unit
            }
        }
    }
    fun walkBlocks(blocks: List<com.lomo.domain.model.markdown.MarkdownRenderBlock>) {
        blocks.forEach { block ->
            when (block) {
                is com.lomo.domain.model.markdown.MarkdownRenderBlock.Paragraph -> walkInlines(block.inlines)
                is com.lomo.domain.model.markdown.MarkdownRenderBlock.Heading -> walkInlines(block.inlines)
                is com.lomo.domain.model.markdown.MarkdownRenderBlock.BlockQuote -> walkBlocks(block.blocks)
                is com.lomo.domain.model.markdown.MarkdownRenderBlock.ListBlock ->
                    block.items.forEach { item -> walkBlocks(item.blocks) }
                is com.lomo.domain.model.markdown.MarkdownRenderBlock.Table -> {
                    (block.header + block.rows.flatten()).forEach { cell -> walkInlines(cell.inlines) }
                }
                else -> Unit
            }
        }
    }
    walkBlocks(document.blocks)
    // Prefer IR image nodes; fall back to attachment destinations when blocks omit media
    // (transport projection without nested image nodes).
    if (count > 0) return count
    return document.attachmentDestinations.count { path ->
        path.isNotEmpty() && !MediaFileExtensions.hasAudioExtension(path)
    }
}

/**
 * Legacy line classifier used only for already-tokenized presentation lines (image markers from
 * IR projection). Not a Markdown semantic authority.
 */
internal fun buildShareBodyLines(
    bodyText: String,
    imagePlaceholder: String,
): List<ShareBodyLine> {
    if (bodyText.isBlank()) {
        return listOf(defaultParagraphLine())
    }

    val lines = mutableListOf<ShareBodyLine>()
    var previousWasBlank = false

    for (rawLine in bodyText.replace('\t', ' ').lineSequence()) {
        if (lines.size >= MAX_SHARE_BODY_LINES) {
            break
        }

        val parsedLine = parseShareBodyLine(rawLine, imagePlaceholder)
        if (parsedLine != null) {
            lines += parsedLine
            previousWasBlank = false
        } else if (!previousWasBlank && lines.isNotEmpty()) {
            lines += ShareBodyLine(BLANK_LAYOUT_TEXT, ShareBodyLineType.Blank)
            previousWasBlank = true
        } else {
            previousWasBlank = true
        }
    }

    return lines.ifEmpty { listOf(defaultParagraphLine()) }
}

private fun defaultParagraphLine(): ShareBodyLine =
    ShareBodyLine(BLANK_LAYOUT_TEXT, ShareBodyLineType.Paragraph)

private fun parseShareBodyLine(
    rawLine: String,
    imagePlaceholder: String,
): ShareBodyLine? {
    val line = rawLine.trimEnd()
    val trimmed = line.trimStart()
    val cleanedLine = replaceInlineImageMarkers(trimmed, imagePlaceholder)
    val imageLine = parseImageMarkerLine(trimmed)

    return when {
        trimmed.isBlank() -> null
        imageLine != null -> imageLine
        line.startsWith(CODE_BLOCK_PREFIX) -> ShareBodyLine(cleanedLine, ShareBodyLineType.Code)
        cleanedLine.startsWith(QUOTE_PREFIX) ->
            ShareBodyLine(
                cleanedLine.removePrefix(QUOTE_PREFIX).trim().normalizeCjkMixedSpacingForDisplay(),
                ShareBodyLineType.Quote,
            )
        cleanedLine.isBulletShareLine() ->
            ShareBodyLine(
                cleanedLine.normalizeCjkMixedSpacingForDisplay(),
                ShareBodyLineType.Bullet,
            )
        else ->
            ShareBodyLine(
                cleanedLine.normalizeCjkMixedSpacingForDisplay(),
                ShareBodyLineType.Paragraph,
            )
    }
}

private fun parseImageMarkerLine(trimmed: String): ShareBodyLine? {
    val markerMatch = IMAGE_MARKER_PATTERN.find(trimmed)
    val imageIndex =
        markerMatch
            ?.groupValues
            ?.get(IMAGE_MARKER_INDEX_GROUP)
            ?.toIntOrNull()
            ?: NO_IMAGE_INDEX

    return markerMatch
        ?.takeIf { trimmed == it.value }
        ?.let { ShareBodyLine(trimmed, ShareBodyLineType.Image, imageIndex = imageIndex) }
}

private fun replaceInlineImageMarkers(
    text: String,
    imagePlaceholder: String,
): String =
    if (IMAGE_MARKER_PATTERN.containsMatchIn(text)) {
        IMAGE_MARKER_PATTERN.replace(text, imagePlaceholder)
    } else {
        text
    }

private fun String.isBulletShareLine(): Boolean =
    startsWith(UNCHECKED_TODO_PREFIX) ||
        startsWith(CHECKED_TODO_PREFIX) ||
        startsWith(BULLET_PREFIX)
