package com.lomo.app.util

import com.lomo.ui.text.normalizeCjkMixedSpacingForDisplay

internal fun preprocessShareCardContent(
    content: String,
    hasImages: Boolean,
): PreprocessedShareCardContent {
    if (!hasImages) {
        return PreprocessedShareCardContent(
            contentForProcessing = content,
            totalImageSlots = 0,
            hasImages = false,
        )
    }

    var nextImageIndex = 0

    fun nextMarker(): String = "\n$IMAGE_MARKER_PREFIX${nextImageIndex++}$IMAGE_MARKER_SUFFIX\n"

    val withWikiImageMarkers = WIKI_IMAGE_REGEX.replace(content) { nextMarker() }
    val contentForProcessing =
        MD_IMAGE_REGEX.replace(withWikiImageMarkers) { match ->
            val path = match.groupValues[MD_IMAGE_PATH_GROUP_INDEX]
            if (path.isAudioPath()) {
                match.value
            } else {
                nextMarker()
            }
        }

    return PreprocessedShareCardContent(
        contentForProcessing = contentForProcessing,
        totalImageSlots = nextImageIndex,
        hasImages = true,
    )
}

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

private fun String.isAudioPath(): Boolean =
    AUDIO_EXTENSIONS.any { extension -> lowercase().endsWith(extension) }

private fun String.isBulletShareLine(): Boolean =
    startsWith(UNCHECKED_TODO_PREFIX) ||
        startsWith(CHECKED_TODO_PREFIX) ||
        startsWith(BULLET_PREFIX)
