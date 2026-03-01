package com.lomo.domain.usecase

import com.lomo.domain.model.ShareAttachmentExtractionResult

class ExtractShareAttachmentsUseCase
    constructor() {
        operator fun invoke(content: String): ShareAttachmentExtractionResult {
            val localAttachmentPaths = extractLocalAttachmentPaths(content)
            return ShareAttachmentExtractionResult(
                localAttachmentPaths = localAttachmentPaths,
                attachmentUris = localAttachmentPaths.associateWith { it },
            )
        }

        private fun extractLocalAttachmentPaths(content: String): List<String> {
            val markdownImages = MARKDOWN_IMAGE_PATTERN.findAll(content).mapNotNull { it.groupValues.getOrNull(1) }
            val wikiImages =
                WIKI_IMAGE_PATTERN.findAll(content).mapNotNull { match ->
                    match.groupValues.getOrNull(1)?.substringBefore('|')
                }
            val audioLinks = AUDIO_LINK_PATTERN.findAll(content).mapNotNull { it.groupValues.getOrNull(1) }

            return (markdownImages + wikiImages + audioLinks)
                .map { it.trim() }
                .filter { path ->
                    path.isNotEmpty() &&
                        !path.startsWith("http://", ignoreCase = true) &&
                        !path.startsWith("https://", ignoreCase = true)
                }.distinct()
                .toList()
        }

        private companion object {
            private val MARKDOWN_IMAGE_PATTERN = Regex("!\\[.*?\\]\\((.*?)\\)")
            private val WIKI_IMAGE_PATTERN = Regex("!\\[\\[(.*?)\\]\\]")
            private val AUDIO_LINK_PATTERN =
                Regex(
                    "(?<!!)\\[[^\\]]*\\]\\((.+?\\.(?:m4a|mp3|ogg|wav|aac))\\)",
                    RegexOption.IGNORE_CASE,
                )
        }
    }
