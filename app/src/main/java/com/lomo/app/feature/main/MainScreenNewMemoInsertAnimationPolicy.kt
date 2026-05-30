package com.lomo.app.feature.main

internal fun isInsertedTopMemoReadyForSpaceStage(
    state: NewMemoInsertAnimationState,
    currentListTopMemoId: String?,
): Boolean {
    if (!state.awaitingInsertedTopMemo) {
        return false
    }
    if (currentListTopMemoId == null || currentListTopMemoId == state.previousTopMemoId) {
        return false
    }
    return true
}
