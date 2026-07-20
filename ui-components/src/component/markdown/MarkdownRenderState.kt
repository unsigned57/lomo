package com.lomo.ui.component.markdown

import com.lomo.domain.model.markdown.MarkdownRenderDocument

/** Explicit result of requesting a Rust-owned Markdown render document. */
sealed interface MarkdownRenderState {
    data object Pending : MarkdownRenderState

    data class Ready(
        val document: MarkdownRenderDocument,
    ) : MarkdownRenderState

    data class Failed(
        val code: String,
    ) : MarkdownRenderState {
        init {
            require(code.isNotBlank()) { "Markdown render failure code must not be blank" }
        }
    }
}
