package com.lomo.app.feature.main

internal fun updateExpandedMemoIds(expandedMemoIds: Set<String>, memoId: String, isExpanded: Boolean): Set<String> =
    if (isExpanded) {
        expandedMemoIds + memoId
    } else {
        expandedMemoIds - memoId
    }

/**
 * Which model a paged row presents for one expansion request.
 *
 * The expand coordinator deliberately keeps [MemoExpandEntry.Expanded] snapshots cached after
 * collapse so a re-expand is instant, so snapshot availability is not row state. Only an active
 * request may present the Full snapshot — otherwise the collapsed preview clip would draw the
 * full body and leak its text into the accessibility tree (D1).
 */
internal fun expandedRowSourceModel(
    previewModel: MemoUiModel,
    expandEntry: MemoExpandEntry?,
    expandRequested: Boolean,
): MemoUiModel = if (expandRequested) {
    when (expandEntry) {
        is MemoExpandEntry.Expanded -> expandEntry.model.withLiveRowMetadata(previewModel)
        is MemoExpandEntry.Refreshing -> expandEntry.previous.withLiveRowMetadata(previewModel)
        is MemoExpandEntry.Loading, is MemoExpandEntry.Failed, null -> previewModel
    }
} else {
    previewModel
}

/**
 * Row-level projection flags (`isPinned`, `isPending`, `isDeleted`) can move without advancing
 * `contentRevision`, so an expanded snapshot always projects the live paging row's metadata.
 */
private fun MemoUiModel.withLiveRowMetadata(previewModel: MemoUiModel): MemoUiModel {
    val liveMemo = previewModel.memo
    if (memo.isPinned == liveMemo.isPinned &&
        memo.isPending == liveMemo.isPending &&
        memo.isDeleted == liveMemo.isDeleted
    ) {
        return this
    }
    return copy(
        memo = memo.copy(
            isPinned = liveMemo.isPinned,
            isPending = liveMemo.isPending,
            isDeleted = liveMemo.isDeleted,
        ),
    )
}

/**
 * The card model a row presents for one expansion state.
 *
 * The Full snapshot's own expand predicate answers a different question ("is this body longer
 * than the card's expand budget"), so it legitimately reports no affordance for a body that the
 * preview still truncates. Once a row has ever expanded, the cached Full model is reused for the
 * collapsed presentation too, so the row-level contract must carry the preview's truncation
 * verdict: the affordance stays on while the row is expanded (collapse path) and while its
 * preview says the body was cut (re-expand path).
 */
internal fun expandedRowCardModel(uiModel: MemoUiModel, affordanceRequired: Boolean): MemoUiModel =
    if (affordanceRequired && !uiModel.shouldShowExpand) {
        uiModel.copy(shouldShowExpand = true)
    } else {
        uiModel
    }
