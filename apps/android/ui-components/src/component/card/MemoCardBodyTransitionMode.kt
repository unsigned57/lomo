package com.lomo.ui.component.card

internal enum class MemoCardBodyTransitionMode {
    Snap,
    StateContentTransform,
}

internal enum class MemoCardBodyVisualState {
    Expanded,
    CollapsedSummary,
    CollapsedMarkdownPreview,
}

internal fun resolveMemoCardBodyTransitionMode(shouldShowExpand: Boolean): MemoCardBodyTransitionMode =
    if (shouldShowExpand) {
        MemoCardBodyTransitionMode.StateContentTransform
    } else {
        MemoCardBodyTransitionMode.Snap
    }

internal fun resolveMemoCardBodyCollapsedTargetPreviewMode(
    bodyTransitionMode: MemoCardBodyTransitionMode,
    currentPreviewMode: MemoCardCollapsedPreviewMode,
    hasProcessedContent: Boolean,
    collapsedSummary: String,
): MemoCardCollapsedPreviewMode =
    when (bodyTransitionMode) {
        MemoCardBodyTransitionMode.Snap -> currentPreviewMode
        MemoCardBodyTransitionMode.StateContentTransform ->
            resolveMemoCardCollapsedPreviewMode(
                isCollapsedPreview = true,
                hasProcessedContent = hasProcessedContent,
                collapsedSummary = collapsedSummary,
            )
    }

internal fun resolveMemoCardBodyVisualState(
    isExpanded: Boolean,
    collapsedPreviewMode: MemoCardCollapsedPreviewMode,
): MemoCardBodyVisualState =
    when {
        isExpanded -> MemoCardBodyVisualState.Expanded
        collapsedPreviewMode == MemoCardCollapsedPreviewMode.Summary -> MemoCardBodyVisualState.CollapsedSummary
        collapsedPreviewMode == MemoCardCollapsedPreviewMode.MarkdownPreview ->
            MemoCardBodyVisualState.CollapsedMarkdownPreview
        else -> MemoCardBodyVisualState.Expanded
    }
