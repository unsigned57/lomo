package com.lomo.app.presentation.sharecard

import com.lomo.app.util.MarkdownCleanupFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShareCardDisplayFormatter
    @Inject
    constructor() {
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
            bodyText: String,
            audioPlaceholder: String,
            imagePlaceholder: String,
            imageNamedPlaceholderPattern: String,
        ): String {
            var str = bodyText.replace("\r\n", "\n")

            str = str.replace(Regex("(?m)^\\s*#{1,2}\\s+"), "✦ ")
            str = str.replace(Regex("(?m)^\\s*#{3,6}\\s+"), "• ")

            str =
                str.replace(Regex("```[\\w-]*\\n([\\s\\S]*?)```")) { match ->
                    val code = match.groupValues[1].trim('\n')
                    if (code.isBlank()) {
                        ""
                    } else {
                        code
                            .lineSequence()
                            .joinToString("\n") { "    $it" }
                    }
                }

            str = str.replace(audioAttachmentPattern, audioPlaceholder)
            str = MarkdownCleanupFormatter.stripForPlainText(str)
            str = str.replace("[Image]", imagePlaceholder)
            str =
                str.replace(Regex("\\[Image:\\s*(.*?)]")) { match ->
                    formatImageNamedPlaceholder(
                        pattern = imageNamedPlaceholderPattern,
                        name = match.groupValues[1],
                    )
                }
            str = str.replace(Regex("`([^`]+)`"), "「$1」")
            str = str.replace(Regex("~~(.*?)~~"), "$1")
            str = str.replace(Regex("(?m)^>\\s?"), "│ ")
            str = str.replace(Regex("(?m)^\\s*\\d+\\.\\s+"), "• ")
            str = str.replace(Regex("(?m)^\\s*[-+*]\\s+"), "• ")
            str = str.replace(Regex("(?m)^\\s*[-*_]{3,}\\s*$"), "")

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

            val audioAttachmentPattern = Regex("!\\[[^\\]]*\\]\\(([^)]+\\.(?:m4a|mp3|aac|wav))\\)", RegexOption.IGNORE_CASE)
        }
    }
