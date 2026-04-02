package com.lomo.ui.component.card

internal fun resolveMemoCardCollapsedPreviewMode(
    isCollapsedPreview: Boolean,
    hasProcessedContent: Boolean,
    collapsedSummary: String,
): MemoCardCollapsedPreviewMode =
    when {
        !isCollapsedPreview -> MemoCardCollapsedPreviewMode.FullContent
        hasProcessedContent -> MemoCardCollapsedPreviewMode.MarkdownPreview
        collapsedSummary.isNotBlank() -> MemoCardCollapsedPreviewMode.Summary
        else -> MemoCardCollapsedPreviewMode.FullContent
    }
