package com.lomo.domain.usecase

object MemoContentFlags {
    private val TODO_REGEX = Regex("""(?m)^[\t ]*[-*][\t ]+\[[ xX]][\t ]+""")
    private val MARKDOWN_IMAGE = Regex("""!\[[^]]*]\([^)]+\)""")
    private val WIKI_IMAGE = Regex("""!\[\[[^]]+]]""")
    private val AUDIO_LINK =
        Regex(
            """(?<!!)\[[^]]*]\([^)]+?\.(?:m4a|mp3|ogg|wav|aac)\)""",
            RegexOption.IGNORE_CASE,
        )
    private val URL_REGEX = Regex("""\bhttps?://\S+""", RegexOption.IGNORE_CASE)

    fun containsTodo(content: String): Boolean = TODO_REGEX.containsMatchIn(content)

    fun containsAttachment(content: String): Boolean =
        MARKDOWN_IMAGE.containsMatchIn(content) ||
            WIKI_IMAGE.containsMatchIn(content) ||
            AUDIO_LINK.containsMatchIn(content)

    fun containsUrl(content: String): Boolean = URL_REGEX.containsMatchIn(content)
}
