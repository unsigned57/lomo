package com.lomo.domain.usecase

import com.lomo.domain.model.ShareCardContent
import com.lomo.domain.model.ShareCardTextInput

/**
 * Domain use case: extract semantic share-card content.
 *
 * It intentionally avoids any UI formatting concerns (placeholder text, typography symbols,
 * markdown presentation style). Those belong to presentation-layer formatters.
 */
class PrepareShareCardContentUseCase
    constructor() {
        operator fun invoke(input: ShareCardTextInput): ShareCardContent {
            val tags = buildShareTags(input.sourceTags, input.content)
            val bodyTextWithoutTags = removeInlineTags(input.content, tags)
            val finalBodyText = bodyTextWithoutTags.trim()
            return ShareCardContent(
                tags = tags,
                bodyText = finalBodyText,
            )
        }

        private fun buildShareTags(
            sourceTags: List<String>,
            content: String,
        ): List<String> {
            val normalized =
                sourceTags
                    .asSequence()
                    .map { it.trim().trimStart('#') }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .toList()
            if (normalized.isNotEmpty()) return normalized

            return inlineTagPattern
                .findAll(content)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
        }

        private fun removeInlineTags(
            content: String,
            tags: List<String>,
        ): String {
            val explicitTags =
                tags
                    .asSequence()
                    .map { it.trim().trimStart('#') }
                    .filter { it.isNotBlank() }
                    .toList()

            var stripped = content
            explicitTags.forEach { tag ->
                val escaped = Regex.escape(tag)
                stripped =
                    stripped.replace(Regex("(^|\\s)#$escaped(?=\\s|$)")) { match ->
                        if (match.value.startsWith(" ") || match.value.startsWith("\t")) {
                            " "
                        } else {
                            ""
                        }
                    }
            }

            stripped =
                stripped.replace(genericInlineTagPattern) { match ->
                    if (match.value.startsWith(" ") || match.value.startsWith("\t")) {
                        " "
                    } else {
                        ""
                    }
                }
            return stripped
        }

        private companion object {
            val inlineTagPattern = Regex("(?:^|\\s)#([\\p{L}\\p{N}_][\\p{L}\\p{N}_/]*)")
            val genericInlineTagPattern = Regex("(^|\\s)#[\\p{L}\\p{N}_][\\p{L}\\p{N}_/]*")
        }
    }
