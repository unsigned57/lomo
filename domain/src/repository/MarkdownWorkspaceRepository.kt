package com.lomo.domain.repository

import com.lomo.domain.model.markdown.MarkdownRenderDocument

/**
 * Domain-facing Markdown capability owned by the active Rust workspace engine session.
 *
 * Implementations must reject calls unless one Ready workspace session is active. The returned
 * document is the validated, presentation-safe projection of the owner IR; consumers must never
 * parse [content] again or fall back to raw Markdown semantics.
 */
interface MarkdownWorkspaceRepository {
    fun renderMarkdown(content: String): MarkdownRenderDocument

    /**
     * Toggles the exact task marker addressed by a Rust-issued render span.
     *
     * The span is relative to the memo body rendered by [renderMarkdown]. Implementations must
     * resolve the memo's current file revision, translate the body-relative span at the owner
     * boundary, and fail closed if the revision or bytes changed.
     */
    suspend fun toggleTask(
        memoIdentity: String,
        actionSpan: com.lomo.domain.model.markdown.MarkdownSourceSpan,
    ): String
}
