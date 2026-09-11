package com.lomo.app.util

/**
 * Presentation spacing helpers for already-projected plain text.
 *
 * Markdown semantic strip is not performed here — callers must supply plain text from the Rust
 * workspace owner IR (`MarkdownRenderDocument.plainText`).
 */
internal object MarkdownCleanupFormatter {
    private val multiSpacePattern = Regex(""" {2,}""")
    private val multiBlankLinePattern = Regex("""\n{3,}""")

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
