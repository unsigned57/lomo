package com.lomo.ui.component.card

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    val onBodyHeightChanged: (Int) -> Unit,
    val renderDocument: MarkdownRenderDocument,
    val presentationPlan: MarkdownIrPresentationPlan?,
    val allowFreeTextCopy: Boolean,
    val onBodyClick: (() -> Unit)?,
    val onLongClick: (() -> Unit)?,
    val onTodoClick: ((MarkdownSourceSpan) -> Unit)?,
    val onImageClick: ((String) -> Unit)?,
    val mediaPresentationResolver: MarkdownMediaPresentationResolver?,
    val mediaContent: (@Composable (MarkdownMediaPresentation) -> Unit)?,
)

@Composable
internal fun rememberMemoCardBodyState(
    onBodyHeightChanged: (Int) -> Unit,
    renderDocument: MarkdownRenderDocument,
    presentationPlan: MarkdownIrPresentationPlan?,
    allowFreeTextCopy: Boolean,
    onBodyClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    onTodoClick: ((MarkdownSourceSpan) -> Unit)?,
    onImageClick: ((String) -> Unit)?,
    mediaPresentationResolver: MarkdownMediaPresentationResolver?,
    mediaContent: (@Composable (MarkdownMediaPresentation) -> Unit)?,
): MemoCardBodyState = remember(
    onBodyHeightChanged,
    renderDocument,
    presentationPlan,
    allowFreeTextCopy,
    onBodyClick,
    onLongClick,
    onTodoClick,
    onImageClick,
    mediaPresentationResolver,
    mediaContent,
) {
    MemoCardBodyState(
        renderDocument = renderDocument,
        onBodyHeightChanged = onBodyHeightChanged,
        presentationPlan = presentationPlan,
        allowFreeTextCopy = allowFreeTextCopy,
        onBodyClick = onBodyClick,
        onLongClick = onLongClick,
        onTodoClick = onTodoClick,
        onImageClick = onImageClick,
        mediaPresentationResolver = mediaPresentationResolver,
        mediaContent = mediaContent,
    )
}
