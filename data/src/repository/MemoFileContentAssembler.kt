package com.lomo.data.repository

/**
 * Detect the dominant line separator used by open Markdown file content.
 *
 * CRLF is preferred when present so unedited write-back can preserve original bytes.
 */
internal fun detectLineSeparator(content: String): String =
    when {
        content.contains("\r\n") -> "\r\n"
        content.contains('\r') -> "\r"
        else -> "\n"
    }

/**
 * Split content into logical lines using the same model as [String.lines] / MarkdownParser.
 *
 * Using [String.lines] (not a raw [String.split]) keeps write-back span indices aligned with parse.
 */
internal fun splitDocumentLines(content: String): List<String> = content.lines()

/**
 * Rejoin lines using [lineSeparator], restoring a trailing separator only when the original
 * open-file text had one and the joined body does not already end with it (avoids double
 * trailing separators when [String.lines] yields a final empty element).
 */
internal fun joinDocumentLines(
    lines: List<String>,
    lineSeparator: String,
    originalContent: String,
): String {
    if (lines.isEmpty()) {
        return if (originalContent.endsWith(lineSeparator)) lineSeparator else ""
    }
    val body = lines.joinToString(lineSeparator)
    return if (originalContent.endsWith(lineSeparator) && !body.endsWith(lineSeparator)) {
        body + lineSeparator
    } else {
        body
    }
}

internal fun buildUpdatedMemoLines(
    newRawContent: String,
    timestampStr: String,
): List<String> {
    val contentLines = newRawContent.lines()
    return when {
        contentLines.size == 1 && contentLines.first().isEmpty() -> listOf("- $timestampStr")
        contentLines.isNotEmpty() ->
            buildList(contentLines.size) {
                add("- $timestampStr ${contentLines.first()}")
                for (index in 1 until contentLines.size) {
                    add(contentLines[index])
                }
            }
        else -> listOf("- $timestampStr")
    }
}

/**
 * Rebuild a document after replacing the inclusive line range [startIndex, endIndex].
 *
 * Prefer [rebuildMemoDocument] when the original open-file text is available so CRLF/LF and
 * trailing separators are preserved for unedited round-trips.
 */
internal fun rebuildMemoContent(
    lines: List<String>,
    startIndex: Int,
    endIndex: Int,
    replacementLines: List<String>,
    lineSeparator: String = "\n",
    originalContent: String = "",
): String {
    val rebuiltLines =
        buildList(lines.size - (endIndex - startIndex + 1) + replacementLines.size) {
            addAll(lines.subList(0, startIndex))
            addAll(replacementLines)
            addAll(lines.subList(endIndex + 1, lines.size))
        }
    val authority =
        originalContent.ifEmpty {
            // Legacy callers without original text: LF-join only (may not preserve CRLF).
            lines.joinToString(lineSeparator)
        }
    return joinDocumentLines(rebuiltLines, lineSeparator, authority)
}

/**
 * Identity-safe document rewrite using the original open-file text as the newline authority.
 */
internal fun rebuildMemoDocument(
    originalContent: String,
    startIndex: Int,
    endIndex: Int,
    replacementLines: List<String>,
): String {
    val lineSeparator = detectLineSeparator(originalContent)
    val lines = splitDocumentLines(originalContent)
    return rebuildMemoContent(
        lines = lines,
        startIndex = startIndex,
        endIndex = endIndex,
        replacementLines = replacementLines,
        lineSeparator = lineSeparator,
        originalContent = originalContent,
    )
}
