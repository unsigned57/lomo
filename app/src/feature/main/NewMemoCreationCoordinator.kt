package com.lomo.app.feature.main

import com.lomo.ui.component.common.EnterRequestId
import com.lomo.ui.component.common.HeadEnterBaseline
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Coordinates the full new-memo insert lifecycle:
 * 1. if not at top, scroll to top (await maybe)
 * 2. invoke `createMemo` (fire-and-forget from caller's perspective; the DB write is async)
 * 3. opportunistically resolve a loaded top baseline and prepare the head-enter animation
 * 4. `awaitNewTopItem` waits until the paging snapshot reflects a new top memo
 *    (which resolves the typed head baseline)
 * 5. `revealNewTopItem` scrolls the list to absolute top so the freshly created memo
 *    lands in the viewport.
 *
 * Paging is presentation state, so baseline resolution is bounded and can only disable animation;
 * it can never prevent the workspace mutation from being submitted.
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
    private val baselineTimeoutMillis: Long = DEFAULT_BASELINE_TIMEOUT_MILLIS,
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
                createMemo(request, isAtTop)
                val baseline = withTimeoutOrNull(baselineTimeoutMillis) { awaitTopBaseline() }
                    ?: return@launch
                preparedEnterRequest = prepareNewTopEnter(baseline)
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

    private companion object {
        const val DEFAULT_BASELINE_TIMEOUT_MILLIS = 250L
    }
}
