package com.lomo.ui.component.card

import androidx.compose.runtime.Composable
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.ui.component.markdown.MarkdownIrPresentationPlan
import com.lomo.ui.component.markdown.MarkdownMediaPresentation
import com.lomo.ui.component.markdown.MarkdownMediaPresentationResolver

/**
 * Groups the markdown rendering inputs and pointer callbacks shared across the memo card body
 * composables so each composable stays within the parameter budget.
 */
internal data class MemoCardBodyState(
    val renderDocument: MarkdownRenderDocument,
    val presentationPlan: MarkdownIrPresentationPlan?,
    val allowFreeTextCopy: Boolean,
    val onTapFeedback: (() -> Unit)?,
    val onBodyClick: (() -> Unit)?,
    val onDoubleClick: (() -> Unit)?,
    val onLongClick: (() -> Unit)?,
    val onTodoClick: ((MarkdownSourceSpan) -> Unit)?,
    val onImageClick: ((String) -> Unit)?,
    val mediaPresentationResolver: MarkdownMediaPresentationResolver?,
    val mediaContent: (@Composable (MarkdownMediaPresentation) -> Unit)?,
)
