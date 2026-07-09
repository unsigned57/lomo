package com.lomo.app.feature.main

internal sealed interface MainScreenFocusRequest {
    data class Immediate(
        val index: Int,
    ) : MainScreenFocusRequest

    data object NotFound : MainScreenFocusRequest
}

internal fun interface MainScreenFocusPositioner {
    suspend fun requestPositionAtItem(index: Int)
}

internal fun resolveMainScreenFocusRequest(
    memoId: String,
    visibleUiMemos: List<MemoUiModel>,
    visibleUiMemoStartIndex: Int = 0,
): MainScreenFocusRequest {
    val localIndex = visibleUiMemos.indexOfFirst { it.memo.id == memoId }
    return if (localIndex >= 0) {
        MainScreenFocusRequest.Immediate(index = visibleUiMemoStartIndex + localIndex)
    } else {
        MainScreenFocusRequest.NotFound
    }
}

internal suspend fun focusMemoInMainScreen(
    memoId: String,
    visibleUiMemos: List<MemoUiModel>,
    visibleUiMemoStartIndex: Int = 0,
    positioner: MainScreenFocusPositioner,
): Boolean =
    when (
        val request =
            resolveMainScreenFocusRequest(
                memoId = memoId,
                visibleUiMemos = visibleUiMemos,
                visibleUiMemoStartIndex = visibleUiMemoStartIndex,
            )
    ) {
        is MainScreenFocusRequest.Immediate -> {
            positioner.requestPositionAtItem(request.index)
            true
        }

        MainScreenFocusRequest.NotFound -> false
    }

internal suspend fun focusMemoInMainScreenWithFallback(
    memoId: String,
    visibleUiMemos: List<MemoUiModel>,
    visibleUiMemoStartIndex: Int = 0,
    canResolveOffscreenMainListFocus: Boolean,
    resolveOffscreenIndex: suspend (String) -> Int?,
    positioner: MainScreenFocusPositioner,
): Boolean {
    if (
        focusMemoInMainScreen(
            memoId = memoId,
            visibleUiMemos = visibleUiMemos,
            visibleUiMemoStartIndex = visibleUiMemoStartIndex,
            positioner = positioner,
        )
    ) {
        return true
    }
    if (!canResolveOffscreenMainListFocus) {
        return false
    }
    val offscreenIndex = resolveOffscreenIndex(memoId) ?: return false
    positioner.requestPositionAtItem(offscreenIndex)
    return false
}
