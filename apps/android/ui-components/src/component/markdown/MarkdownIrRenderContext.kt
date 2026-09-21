package com.lomo.ui.component.markdown

import androidx.compose.runtime.Composable
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.ui.text.MemoTextSelectionRegistrar

/** Shared rendering options threaded through the Markdown IR composables. */
internal data class MarkdownIrRenderContext(
    val onTaskClick: ((MarkdownSourceSpan) -> Unit)?,
    val onImageClick: ((String) -> Unit)?,
    val mediaPresentationResolver: MarkdownMediaPresentationResolver?,
    val enableTextSelection: Boolean,
    val textSelectionRegistrar: MemoTextSelectionRegistrar?,
    val onTextTapFeedback: (() -> Unit)?,
    val onTextBodyClick: (() -> Unit)?,
    val onTextDoubleClick: (() -> Unit)?,
    val onTextLongClick: (() -> Unit)?,
    val hideImages: Boolean,
    val mediaContent: (@Composable (MarkdownMediaPresentation) -> Unit)?,
)
