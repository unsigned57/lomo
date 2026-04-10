package com.lomo.app.feature.main

internal sealed interface MainScreenFocusRequest {
    data class Immediate(
        val index: Int,
    ) : MainScreenFocusRequest

    data object NotFound : MainScreenFocusRequest
}

internal fun interface MainScreenFocusScroller {
    suspend fun scrollToItem(index: Int)
}

internal fun resolveMainScreenFocusRequest(
    memoId: String,
    visibleUiMemos: List<MemoUiModel>,
): MainScreenFocusRequest {
    val index = visibleUiMemos.indexOfFirst { it.memo.id == memoId }
    return if (index >= 0) {
        MainScreenFocusRequest.Immediate(index = index)
    } else {
        MainScreenFocusRequest.NotFound
    }
}

internal suspend fun focusMemoInMainScreen(
    memoId: String,
    visibleUiMemos: List<MemoUiModel>,
    scroller: MainScreenFocusScroller,
): Boolean =
    when (val request = resolveMainScreenFocusRequest(memoId = memoId, visibleUiMemos = visibleUiMemos)) {
        is MainScreenFocusRequest.Immediate -> {
            scroller.scrollToItem(request.index)
            true
        }

        MainScreenFocusRequest.NotFound -> false
    }
