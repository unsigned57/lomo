package com.lomo.app.util

internal object MarkdownCleanupFormatter {
    private val headingPattern = Regex("(?m)^#{1,6}\\s+")
    private val boldPattern = Regex("(\\*\\*|__)")
    private val uncheckedTaskPattern = Regex("(?m)^\\s*[-*+]\\s*\\[ \\]")
    private val checkedTaskPattern = Regex("(?m)^\\s*[-*+]\\s*\\[x\\]")
    private val markdownImagePattern = Regex("!\\[.*?\\]\\(.*?\\)")
    private val wikiImagePattern = Regex("!\\[\\[(.*?)\\]\\]")
    private val markdownLinkPattern = Regex("(?<!!)\\[(.*?)\\]\\(.*?\\)")
    private val listItemPattern = Regex("(?m)^\\s*[-*+]\\s+")
    private val multiSpacePattern = Regex(" {2,}")
    private val multiBlankLinePattern = Regex("\\n{3,}")

    fun stripForPlainText(content: String): String {
        var str = content
        str = str.replace(headingPattern, "")
        str = str.replace(boldPattern, "")
        str = str.replace(uncheckedTaskPattern, "☐")
        str = str.replace(checkedTaskPattern, "☑")
        str = str.replace(markdownImagePattern, "[Image]")
        str = str.replace(wikiImagePattern, "[Image: $1]")
        str = str.replace(markdownLinkPattern, "$1")
        str = str.replace(listItemPattern, "• ")
        return str.trim()
    }

    fun collapseSpacing(
        content: String,
        trim: Boolean = true,
    ): String {
        val normalized =
            content
                .replace(multiSpacePattern, " ")
                .replace(multiBlankLinePattern, "\n\n")
        return if (trim) normalized.trim() else normalized
    }
}
