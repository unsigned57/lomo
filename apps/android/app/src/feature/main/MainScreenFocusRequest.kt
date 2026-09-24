package com.lomo.app.feature.main

import com.lomo.domain.model.MemoListFilter

/**
 * Query/filter epoch for focus retries. A queued focus request is only worth retrying inside the
 * epoch it was issued under; a query or structural filter change produces a different epoch.
 */
internal data class MainListQueryEpoch(
    val searchQuery: String,
    val filter: MemoListFilter,
)

/**
 * Bounded retry discriminator for queued focus requests.
 *
 * The key is bound to the query epoch, the visible window's bounds (start index plus first/last
 * identity), and whether the target is already visible — never to an enumeration of the window's
 * rows, which would cost O(loaded) key material on every composition pass.
 */
internal data class MainListFocusRetryKey(
    val queryEpoch: MainListQueryEpoch,
    val windowStartIndex: Int,
    val windowFirstMemoId: String?,
    val windowLastMemoId: String?,
    val targetVisible: Boolean,
)

internal fun resolveMainListFocusRetryKey(
    searchQuery: String,
    filter: MemoListFilter,
    windowStartIndex: Int,
    visibleMemos: List<MemoUiModel>,
    pendingFocusMemoIds: Set<String>,
): MainListFocusRetryKey =
    MainListFocusRetryKey(
        queryEpoch = MainListQueryEpoch(searchQuery = searchQuery, filter = filter),
        windowStartIndex = windowStartIndex,
        windowFirstMemoId = visibleMemos.firstOrNull()?.run { memo.id },
        windowLastMemoId = visibleMemos.lastOrNull()?.run { memo.id },
        targetVisible = visibleMemos.any { it.memo.id in pendingFocusMemoIds },
    )

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
