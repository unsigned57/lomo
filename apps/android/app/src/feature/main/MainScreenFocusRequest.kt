package com.lomo.app.feature.main

internal sealed interface MainScreenFocusRequest {
    data class Immediate(
        val index: Int,
    ) : MainScreenFocusRequest

    data object NotFound : MainScreenFocusRequest
}

internal sealed interface MainScreenFocusAttempt {
    data object Placed : MainScreenFocusAttempt

    data object WaitingForPage : MainScreenFocusAttempt

    data object Missing : MainScreenFocusAttempt
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
): MainScreenFocusAttempt {
    if (
        focusMemoInMainScreen(
            memoId = memoId,
            visibleUiMemos = visibleUiMemos,
            visibleUiMemoStartIndex = visibleUiMemoStartIndex,
            positioner = positioner,
        )
    ) {
        return MainScreenFocusAttempt.Placed
    }
    if (!canResolveOffscreenMainListFocus) {
        return MainScreenFocusAttempt.Missing
    }
    val offscreenIndex = resolveOffscreenIndex(memoId) ?: return MainScreenFocusAttempt.Missing
    positioner.requestPositionAtItem(offscreenIndex)
    return MainScreenFocusAttempt.WaitingForPage
}
