package com.lomo.app.feature.main

internal fun isInsertedTopMemoReadyForSpaceStage(
    state: NewMemoInsertAnimationState,
    currentListTopMemoId: String?,
    isListPinnedAtTop: Boolean,
): Boolean {
    if (!state.awaitingInsertedTopMemo || !isListPinnedAtTop) {
        return false
    }
    if (currentListTopMemoId == null || currentListTopMemoId == state.previousTopMemoId) {
        return false
    }
    return true
}
