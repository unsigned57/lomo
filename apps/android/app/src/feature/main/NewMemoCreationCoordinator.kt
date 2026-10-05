package com.lomo.app.feature.main

import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import com.lomo.ui.component.common.EnterRequestId
import com.lomo.ui.component.common.HeadEnterBaseline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

/** Inputs for one new-memo reveal cycle: the scroll/enter handles plus the head-rank oracle. */
internal data class NewMemoCreationCoordinatorDependencies<T>(
    val scope: CoroutineScope,
    val isListAtAbsoluteTop: () -> Boolean,
    val scrollListToAbsoluteTop: suspend () -> Unit,
    val readTopBaseline: () -> HeadEnterBaseline?,
    val prepareNewTopEnter: (HeadEnterBaseline) -> EnterRequestId,
    val createMemo: suspend (request: T, wasAtTop: Boolean) -> String?,
    val newHeadRank: suspend (memoId: String) -> Int?,
    val awaitNewTopItem: suspend (HeadEnterBaseline) -> String?,
    val revealNewTopItem: suspend (newTopId: String) -> Unit,
    val cancelPreparedEnter: (EnterRequestId) -> Unit,
    val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
)

/**
 * Coordinates the full new-memo insert lifecycle:
 * 1. read the currently available top baseline synchronously — the durable commit never waits on
 *    the viewport, and a baseline sampled after submission can never masquerade as pre-commit state
 * 2. submit and await the acknowledged durable `createMemo` result, which yields the new memo id
 * 3. if not at top, scroll to top for presentation
 * 4. `newHeadRank` asks the owning query engine whether the committed memo is the list head under
 *    the active spec; `awaitNewTopItem`/`revealNewTopItem` run only when it is rank 0, so a memo
 *    the current filter, ordering or pinned head can never surface never triggers a bounded wait
 * 5. `revealNewTopItem` scrolls the list to absolute top so the freshly created memo
 *    lands in the viewport.
 *
 * Paging is presentation state: the baseline only feeds the enter animation and the rank oracle
 * only feeds the reveal wait — neither can delay or suppress the workspace mutation itself.
 */
internal class NewMemoCreationCoordinator<T>(
    dependencies: NewMemoCreationCoordinatorDependencies<T>,
) {
    private val scope = dependencies.scope
    private val isListAtAbsoluteTop = dependencies.isListAtAbsoluteTop
    private val scrollListToAbsoluteTop = dependencies.scrollListToAbsoluteTop
    private val readTopBaseline = dependencies.readTopBaseline
    private val prepareNewTopEnter = dependencies.prepareNewTopEnter
    private val createMemo = dependencies.createMemo
    private val newHeadRank = dependencies.newHeadRank
    private val awaitNewTopItem = dependencies.awaitNewTopItem
    private val revealNewTopItem = dependencies.revealNewTopItem
    private val cancelPreparedEnter = dependencies.cancelPreparedEnter
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
                val baseline = readTopBaseline()
                if (baseline != null) {
                    preparedEnterRequest = prepareNewTopEnter(baseline)
                }
                val memoId = createMemo(request, wasAtTop)
                if (memoId == null) {
                    return@launch
                }
                try {
                    if (!isListAtAbsoluteTop()) {
                        scrollListToAbsoluteTop()
                    }
                    if (baseline != null && newHeadRank(memoId) == 0) {
                        val newTopId = awaitNewTopItem(baseline)
                        if (newTopId != null) {
                            revealNewTopItem(newTopId)
                            preparedEnterResolved = true
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // The durable commit already landed; scroll/rank/await/reveal are
                    // presentation-only. A throwing oracle (engine mid-switch, failing scroll
                    // or reveal) degrades to "no reveal" — it must never escape the launch as an
                    // unhandled coroutine failure.
                    Timber.w(error, "New-memo reveal degraded: presentation step failed after commit")
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
