package com.lomo.app.feature.main

import com.lomo.ui.component.common.EnterRequestId
import com.lomo.ui.component.common.HeadEnterBaseline
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Coordinates the full new-memo insert lifecycle:
 * 1. if not at top, scroll to top (await maybe)
 * 2. await a loaded top baseline and prepare the pending head-enter animation request
 * 3. invoke `createMemo` (fire-and-forget from caller's perspective; the DB write is async)
 * 4. `awaitNewTopItem` waits until the paging snapshot reflects a new top memo
 *    (which resolves the typed head baseline)
 * 5. `revealNewTopItem` scrolls the list to absolute top so the freshly created memo
 *    lands in the viewport.
 *
 * Preparing before `createMemo` fixes the race where the list first saw the newly-prepended
 * id before `beginEnter(newId)` ran, allowing a full-opacity first frame and a later snap
 * to the top.
 */
internal class NewMemoCreationCoordinator<T>(
    private val scope: CoroutineScope,
    private val isListAtAbsoluteTop: () -> Boolean,
    private val scrollListToAbsoluteTop: suspend () -> Unit,
    private val awaitTopBaseline: suspend () -> HeadEnterBaseline,
    private val prepareNewTopEnter: (HeadEnterBaseline) -> EnterRequestId,
    private val createMemo: (request: T, wasAtTop: Boolean) -> Unit,
    private val awaitNewTopItem: suspend (HeadEnterBaseline) -> String?,
    private val revealNewTopItem: suspend (newTopId: String) -> Unit,
    private val cancelPreparedEnter: (EnterRequestId) -> Unit,
) {
    private var submissionInFlight = false

    fun submit(request: T): Boolean {
        if (submissionInFlight) {
            return false
        }

        submissionInFlight = true
        scope.launch(context = Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            var preparedEnterRequest: EnterRequestId? = null
            var preparedEnterResolved = false
            try {
                if (!isListAtAbsoluteTop()) {
                    scrollListToAbsoluteTop()
                }
                val isAtTop = isListAtAbsoluteTop()
                val baseline = awaitTopBaseline()
                preparedEnterRequest = prepareNewTopEnter(baseline)
                createMemo(request, isAtTop)
                val newTopId = awaitNewTopItem(baseline)
                if (newTopId != null) {
                    revealNewTopItem(newTopId)
                    preparedEnterResolved = true
                }
            } finally {
                if (!preparedEnterResolved) {
                    preparedEnterRequest?.let(cancelPreparedEnter)
                }
                submissionInFlight = false
            }
        }
        return true
    }
}
