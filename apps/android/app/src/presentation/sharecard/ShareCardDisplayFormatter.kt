package com.lomo.app.presentation.sharecard

import com.lomo.app.util.MarkdownCleanupFormatter

/**
 * Presentation-only share-card formatter.
 *
 * [formatBodyText] expects already-projected plain text from the Rust workspace owner IR
 * (`MarkdownRenderDocument.plainText`). It must not re-parse Markdown semantics.
 */
class ShareCardDisplayFormatter {
    fun formatTagsForDisplay(tags: List<String>): List<String> =
        tags
            .asSequence()
            .map { it.trim().trimStart('#') }
            .filter { it.isNotBlank() }
            .map { it.take(MAX_TAG_LENGTH) }
            .distinct()
            .take(MAX_TAG_COUNT)
            .toList()

    fun formatBodyText(
        plainBodyText: String,
        audioPlaceholder: String,
        imagePlaceholder: String,
        imageNamedPlaceholderPattern: String,
    ): String {
        var str = plainBodyText.replace("\r\n", "\n")
        // IR plain text may still carry attachment path tokens as literal text for share layout.
        str = str.replace(audioPathTokenPattern, audioPlaceholder)
        str = str.replace(imagePathTokenPattern, imagePlaceholder)
        str =
            str.replace(namedImageTokenPattern) { match ->
                formatImageNamedPlaceholder(
                    pattern = imageNamedPlaceholderPattern,
                    name = match.groupValues[1],
                )
            }
        str =
            str
                .lineSequence()
                .joinToString("\n") { line ->
                    val trimmedRight = line.trimEnd()
                    if (trimmedRight.startsWith("    ")) {
                        trimmedRight
                    } else {
                        MarkdownCleanupFormatter.collapseSpacing(trimmedRight, trim = false)
                    }
                }
        return MarkdownCleanupFormatter.collapseSpacing(str)
    }

    private fun formatImageNamedPlaceholder(
        pattern: String,
        name: String,
    ): String =
        runCatching {
            pattern.format(name)
        }.getOrElse {
            "$pattern $name"
        }

    private companion object {
        const val MAX_TAG_LENGTH = 18
        const val MAX_TAG_COUNT = 6

        // Presentation token patterns only — not Markdown structure recognition.
        val audioPathTokenPattern =
            Regex(
                """\b[\w./-]+\.(?:m4a|mp3|aac|wav)\b""",
                RegexOption.IGNORE_CASE,
            )
        val imagePathTokenPattern =
            Regex(
                """\b[\w./-]+\.(?:png|jpe?g|gif|webp|bmp)\b""",
                RegexOption.IGNORE_CASE,
            )
        val namedImageTokenPattern = Regex("""\[Image:\s*(.*?)]""")
    }
}
