package com.lomo.app.feature.trash

import com.lomo.app.feature.main.MemoUiModel
import com.lomo.ui.component.common.LomoListExitRenderEntry
import kotlinx.collections.immutable.ImmutableList

internal fun trashOverlayItems(
    overlayIdle: Boolean,
    snapshotMemos: List<MemoUiModel>,
    renderList: ImmutableList<LomoListExitRenderEntry<MemoUiModel>>,
): List<MemoUiModel> =
    if (overlayIdle) {
        snapshotMemos
    } else {
        renderList.map { entry -> entry.item }
    }

internal fun trashAnchorAfter(
    items: List<MemoUiModel>,
    memoId: String,
): String? {
    val index = items.indexOfFirst { uiModel -> uiModel.memo.id == memoId }
    return if (index > 0) items[index - 1].memo.id else null
}
