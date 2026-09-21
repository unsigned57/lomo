package com.lomo.app.feature.main

import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import com.lomo.ui.component.common.EnterRequestId
import com.lomo.ui.component.common.HeadEnterBaseline
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val DEFAULT_BASELINE_TIMEOUT_MILLIS = 250L

/** Inputs for one new-memo reveal cycle: the scroll/enter handles plus the bounded timeout policy. */
internal data class NewMemoCreationCoordinatorDependencies<T>(
    val scope: CoroutineScope,
    val isListAtAbsoluteTop: () -> Boolean,
    val scrollListToAbsoluteTop: suspend () -> Unit,
    val awaitTopBaseline: suspend () -> HeadEnterBaseline,
    val prepareNewTopEnter: (HeadEnterBaseline) -> EnterRequestId,
    val createMemo: suspend (request: T, wasAtTop: Boolean) -> Boolean,
    val awaitNewTopItem: suspend (HeadEnterBaseline) -> String?,
    val revealNewTopItem: suspend (newTopId: String) -> Unit,
    val cancelPreparedEnter: (EnterRequestId) -> Unit,
    val baselineTimeoutMillis: Long = DEFAULT_BASELINE_TIMEOUT_MILLIS,
    val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
)

/**
 * Coordinates the full new-memo insert lifecycle:
 * 1. submit and await the acknowledged durable `createMemo` result
 * 2. if not at top, scroll to top for presentation
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
    dependencies: NewMemoCreationCoordinatorDependencies<T>,
) {
    private val scope = dependencies.scope
    private val isListAtAbsoluteTop = dependencies.isListAtAbsoluteTop
    private val scrollListToAbsoluteTop = dependencies.scrollListToAbsoluteTop
    private val awaitTopBaseline = dependencies.awaitTopBaseline
    private val prepareNewTopEnter = dependencies.prepareNewTopEnter
    private val createMemo = dependencies.createMemo
    private val awaitNewTopItem = dependencies.awaitNewTopItem
    private val revealNewTopItem = dependencies.revealNewTopItem
    private val cancelPreparedEnter = dependencies.cancelPreparedEnter
    private val baselineTimeoutMillis = dependencies.baselineTimeoutMillis
    private val dispatcherProvider = dependencies.dispatcherProvider
    private var submissionInFlight = false

    fun submit(request: T): Boolean {
        if (submissionInFlight) {
            return false
        }

        submissionInFlight = true
        scope.launch(context = dispatcherProvider.unconfined, start = CoroutineStart.UNDISPATCHED) {
            var preparedEnterRequest: EnterRequestId? = null
            var preparedEnterResolved = false
            try {
                val wasAtTop = isListAtAbsoluteTop()
                val baseline = withTimeoutOrNull(baselineTimeoutMillis) { awaitTopBaseline() }
                if (baseline != null) {
                    preparedEnterRequest = prepareNewTopEnter(baseline)
                }
                if (!createMemo(request, wasAtTop)) {
                    return@launch
                }
                if (!isListAtAbsoluteTop()) {
                    scrollListToAbsoluteTop()
                }
                if (baseline != null) {
                    val newTopId = awaitNewTopItem(baseline)
                    if (newTopId != null) {
                        revealNewTopItem(newTopId)
                        preparedEnterResolved = true
                    }
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
