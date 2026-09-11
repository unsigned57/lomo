package com.lomo.ui.component.markdown

/**
 * Presentation policy applied when projecting a Rust-issued [com.lomo.domain.model.markdown.MarkdownRenderDocument]
 * into a Compose layout plan.
 *
 * This policy controls UI visibility, whitespace normalization, and empty block pruning. It never mutates
 * the underlying domain model or re-parses raw Markdown source text.
 */
data class MarkdownPresentationPolicy(
    val hideTags: Boolean = false,
    val hideReminders: Boolean = false,
    val pruneEmptyBlocks: Boolean = true,
    val normalizeWhitespace: Boolean = true,
) {
    companion object {
        val DEFAULT = MarkdownPresentationPolicy()
        val MEMO_CARD =
            MarkdownPresentationPolicy(
                hideTags = true,
                hideReminders = true,
                pruneEmptyBlocks = true,
                normalizeWhitespace = true,
            )
    }
}
