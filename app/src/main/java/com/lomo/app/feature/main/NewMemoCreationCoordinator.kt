package com.lomo.app.feature.main

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Coordinates the full new-memo insert lifecycle:
 * 1. if not at top, scroll to top (await maybe)
 * 2. invoke `createMemo` (fire-and-forget from caller's perspective; the DB write is async)
 * 3. `awaitNewTopItemAndReveal` waits until the paging snapshot reflects a new top memo
 *    (which is different from `previousTopId`) and then scrolls the list to absolute top
 *    so the freshly created memo lands in the viewport.
 *
 * This fixes the race where the caller invoked `revealNewTopItem` in the same frame as
 * the fire-and-forget `createMemo`, causing `scrollToItem(0)` to pin the *old* top item,
 * then the async DB insert prepended the new item above it, leaving it out of view.
 */
internal class NewMemoCreationCoordinator<T>(
    private val scope: CoroutineScope,
    private val isListAtAbsoluteTop: () -> Boolean,
    private val scrollListToAbsoluteTop: suspend () -> Unit,
    private val createMemo: (request: T, wasAtTop: Boolean) -> Unit,
    private val currentTopMemoId: () -> String?,
    private val awaitNewTopItemAndReveal: suspend (previousTopId: String?) -> Unit,
) {
    private var submissionInFlight = false

    fun submit(request: T): Boolean {
        if (submissionInFlight) {
            return false
        }

        submissionInFlight = true
        scope.launch(context = Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try {
                if (!isListAtAbsoluteTop()) {
                    scrollListToAbsoluteTop()
                }
                val isAtTop = isListAtAbsoluteTop()
                val previousTopId = currentTopMemoId()
                createMemo(request, isAtTop)
                awaitNewTopItemAndReveal(previousTopId)
            } finally {
                submissionInFlight = false
            }
        }
        return true
    }
}
