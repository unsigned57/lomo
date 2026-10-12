package com.lomo.ui.component.markdown

import androidx.compose.runtime.Composable
import com.lomo.domain.model.MediaClass

/** Presentation projection of a media embed already identified by the Rust render IR. */
data class MarkdownPresentationImage(
    val destination: String,
    val description: String? = null,
    val mediaClass: MediaClass,
)

data class MarkdownMediaPresentation(
    val source: String,
    val description: String? = null,
    val kind: String,
)

typealias MarkdownMediaPresentationResolver =
    (MarkdownPresentationImage) -> MarkdownMediaPresentation?

data class MarkdownMediaPresentationAdapter(
    val resolver: MarkdownMediaPresentationResolver,
    val content: @Composable (MarkdownMediaPresentation) -> Unit,
)
