package com.lomo.ui.component.markdown

object MarkdownKnownTagFilter {
    private val HORIZONTAL_WHITESPACE_REGEX = Regex("[ \\t]{2,}")
    private val SPACE_BEFORE_PUNCTUATION_REGEX = Regex("[ \\t]+(?=[.,!?;:，。！？；：、)\\]}】）])")
    private val SPACE_BEFORE_NEWLINE_REGEX = Regex("[ \\t]+(?=\\n)")
    private val LEADING_EMPTY_LINES_REGEX = Regex("^(?:[ \\t]*\\n)+")

    fun eraseKnownTags(
        root: ImmutableNode,
        tags: Iterable<String>,
    ): ImmutableNode {
        val sanitized = sanitizeModernMarkdownKnownTags(content = root.content, tags = tags)
        return ImmutableNode(
            node = sanitized.reusableRoot ?: parseModernMarkdownDocument(sanitized.content),
            content = sanitized.content,
        )
    }

    fun stripInlineTags(
        input: String,
        tags: Iterable<String>,
    ): String {
        val tagPatterns = buildTagPatterns(tags)
        return eraseTagsInText(input = input, tagPatterns = tagPatterns)
    }

    private fun buildTagPatterns(tags: Iterable<String>): List<Regex> =
        tags
            .asSequence()
            .map { it.trim().trimStart('#').trimEnd('/') }
            .filter { it.isNotBlank() }
            .distinct()
            .map(::createTagPattern)
            .toList()
    private fun eraseTagsInText(
        input: String,
        tagPatterns: List<Regex>,
    ): String {
        val shouldStrip = tagPatterns.isNotEmpty() && '#' in input
        if (!shouldStrip) return input

        var stripped = input
        var changed = false

        tagPatterns.forEach { pattern ->
            stripped =
                stripped.replace(pattern) { match ->
                    changed = true
                    match.groupValues[1]
                }
        }

        return if (changed) {
            stripped
                .replace(HORIZONTAL_WHITESPACE_REGEX, " ")
                .replace(SPACE_BEFORE_PUNCTUATION_REGEX, "")
                .replace(SPACE_BEFORE_NEWLINE_REGEX, "\n")
                .replace(LEADING_EMPTY_LINES_REGEX, "")
        } else {
            input
        }
    }

    private fun createTagPattern(tag: String): Regex =
        Regex("""(^|\s)#${Regex.escape(tag)}(?:/)?(?=\s|$|[.,!?;:，。！？；：、)\]}】）])""")
}
