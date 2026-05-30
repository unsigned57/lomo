package com.lomo.domain.usecase

import com.lomo.domain.model.MemoContentAnalysis

object MemoContentAnalyzer {
    fun analyze(content: String): MemoContentAnalysis =
        extractAttachmentTargets(content).let { attachments ->
            MemoContentAnalysis(
                hasTodo = TODO_REGEX.containsMatchIn(content),
                hasAttachment = attachments.hasAny,
                hasUrl = URL_REGEX.containsMatchIn(content) ||
                    GEO_REGEX.containsMatchIn(content) ||
                    EMAIL_REGEX.containsMatchIn(content),
                tags = extractTags(content),
                imageUrls = attachments.imageUrls,
                audioUrls = attachments.audioUrls,
            )
        }

    internal fun containsTodo(content: String): Boolean = TODO_REGEX.containsMatchIn(content)

    internal fun containsUrl(content: String): Boolean =
        URL_REGEX.containsMatchIn(content) ||
            GEO_REGEX.containsMatchIn(content) ||
            EMAIL_REGEX.containsMatchIn(content)

    internal fun extractTags(content: String): List<String> =
        TAG_PATTERN
            .findAll(content)
            .mapNotNull { match ->
                match.groupValues[TAG_VALUE_GROUP]
                    .trimEnd(TAG_PATH_SEPARATOR)
                    .takeUnless { tag -> tag.isNullOrEmpty() }
            }.distinct()
            .toList()

    internal fun extractAttachmentTargets(content: String): AttachmentTargets {
        val imageTargets =
            MARKDOWN_IMAGE.findAll(content).mapNotNull { match ->
                match.groupValues[MARKDOWN_IMAGE_TARGET_GROUP]
                    .takeUnless(String::isBlank)
                    ?.let { target -> AttachmentTarget(match.range.first, target.trim()) }
            } +
                WIKI_IMAGE.findAll(content).mapNotNull { match ->
                    match.groupValues[WIKI_IMAGE_TARGET_GROUP]
                        .substringBefore(WIKI_IMAGE_ALT_SEPARATOR)
                        .trim()
                        .takeUnless(String::isBlank)
                        ?.let { target -> AttachmentTarget(match.range.first, target) }
                }
        val audioTargets =
            AUDIO_LINK.findAll(content).mapNotNull { match ->
                match.groupValues[AUDIO_LINK_TARGET_GROUP]
                    .takeUnless(String::isBlank)
                    ?.let { target -> AttachmentTarget(match.range.first, target.trim()) }
            }

        return AttachmentTargets(
            imageUrls = imageTargets.toSourceOrderedDistinctValues(),
            audioUrls = audioTargets.toSourceOrderedDistinctValues(),
        )
    }

    internal data class AttachmentTargets(
        val imageUrls: List<String>,
        val audioUrls: List<String>,
    ) {
        val hasAny: Boolean
            get() = imageUrls.isNotEmpty() || audioUrls.isNotEmpty()
    }

    private data class AttachmentTarget(
        val sourceIndex: Int,
        val value: String,
    )

    private fun Sequence<AttachmentTarget>.toSourceOrderedDistinctValues(): List<String> =
        sortedBy(AttachmentTarget::sourceIndex)
            .map(AttachmentTarget::value)
            .distinct()
            .toList()

    private val TODO_REGEX = Regex("""(?m)^[\t ]*[-*][\t ]+\[[ xX]][\t ]+""")
    private val MARKDOWN_IMAGE = Regex("""!\[[^]]*]\(([^)]+)\)""")
    private val WIKI_IMAGE = Regex("""!\[\[([^]]+)]]""")
    private val AUDIO_LINK =
        Regex(
            """(?<!!)\[[^]]*]\(([^)]+?\.(?:m4a|mp3|ogg|wav|aac))\)""",
            RegexOption.IGNORE_CASE,
        )
    private val URL_REGEX = Regex("""\bhttps?://\S+""", RegexOption.IGNORE_CASE)
    private val GEO_REGEX =
        Regex(
            """\bgeo:-?\d+(\.\d+)?,?-?\d+(\.\d+)?\S*""",
            RegexOption.IGNORE_CASE,
        )
    private val EMAIL_REGEX =
        Regex(
            """\b(?:mailto:)?[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b""",
            RegexOption.IGNORE_CASE,
        )
    private val TAG_PATTERN =
        Regex(
            """(?:^|\s)#([\p{L}\p{N}\p{So}\p{Sc}_][\p{L}\p{N}\p{So}\p{Sc}_/]*)(?=$|[\s,])""",
        )
    private const val MARKDOWN_IMAGE_TARGET_GROUP = 1
    private const val WIKI_IMAGE_TARGET_GROUP = 1
    private const val AUDIO_LINK_TARGET_GROUP = 1
    private const val TAG_VALUE_GROUP = 1
    private const val WIKI_IMAGE_ALT_SEPARATOR = '|'
    private const val TAG_PATH_SEPARATOR = '/'
}
