package com.lomo.app.feature.main

import com.lomo.ui.component.common.EnterRequestId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import timber.log.Timber
import kotlin.time.TimeSource

/** Inputs for one new-memo reveal cycle: the scroll/enter handles plus the query-rank oracle. */
internal data class NewMemoCreationCoordinatorDependencies<T>(
    val scope: CoroutineScope,
    val isListAtAbsoluteTop: () -> Boolean,
    val scrollListToAbsoluteTop: suspend () -> Unit,
    val readBaselineKeys: () -> Set<String>,
    val prepareEnter: (Set<String>) -> EnterRequestId,
    val bindCommittedEnter: (EnterRequestId, String) -> Unit,
    val startPreparedEnter: (EnterRequestId) -> Unit,
    /**
     * Runs the durable create. `onAccepted` may be called from any thread once the engine admits
     * the create and its pending row is queryable; the return is the committed id, or null.
     */
    val createMemo: suspend (request: T, wasAtTop: Boolean, onAccepted: (memoId: String) -> Unit) -> String?,
    val createdMemoRank: suspend (memoId: String) -> Int?,
    val awaitCreatedItem: suspend (String, Int) -> Boolean,
    val revealCreatedItem: suspend (memoId: String, rank: Int) -> Boolean,
    val cancelPreparedEnter: (EnterRequestId) -> Unit,
    /** Ends a started enter whose row was withdrawn because the durable commit failed. */
    val releaseEnter: (memoId: String) -> Unit,
)

/**
 * Return-to-top starts with submission, concurrently with the durable write. Presentation starts
 * from the engine's acceptance — the moment the created memo is queryable as a pending row — so
 * the reveal never waits on platform I/O. Only an exact ranked identity and viewport
 * acknowledgement may release that row into its enter animation. A commit that fails after
 * acceptance withdraws the row: an unfinished reveal is cancelled and a started enter released.
 * Failure/cancellation always releases the hold, and the slot frees once the write settles.
 */
internal class NewMemoCreationCoordinator<T>(dependencies: NewMemoCreationCoordinatorDependencies<T>) {
    private val scope = dependencies.scope
    private val isListAtAbsoluteTop = dependencies.isListAtAbsoluteTop
    private val scrollListToAbsoluteTop = dependencies.scrollListToAbsoluteTop
    private val readBaselineKeys = dependencies.readBaselineKeys
    private val prepareEnter = dependencies.prepareEnter
    private val bindCommittedEnter = dependencies.bindCommittedEnter
    private val startPreparedEnter = dependencies.startPreparedEnter
    private val createMemo = dependencies.createMemo
    private val createdMemoRank = dependencies.createdMemoRank
    private val awaitCreatedItem = dependencies.awaitCreatedItem
    private val revealCreatedItem = dependencies.revealCreatedItem
    private val cancelPreparedEnter = dependencies.cancelPreparedEnter
    private val releaseEnter = dependencies.releaseEnter
    private var submissionInFlight = false

    fun submit(request: T): Boolean {
        if (submissionInFlight) {
            return false
        }

        submissionInFlight = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val started = TimeSource.Monotonic.markNow()
            fun recordStage(stage: String) {
                Timber.tag("LomoCreation").d("stage=%s elapsed_ms=%d", stage, started.elapsedNow().inWholeMilliseconds)
            }
            var preparedEnterRequest: EnterRequestId? = null
            var preparedEnterResolved = false
            var returnToTop: Deferred<Result<Unit>>? = null
            var presentation: Deferred<Boolean>? = null
            var enteredMemoId: String? = null
            var durablyCommitted = false
            try {
                val wasAtTop = isListAtAbsoluteTop()
                recordStage(if (wasAtTop) "submit_at_top" else "submit_below_top")
                val enterRequest = prepareEnter(readBaselineKeys())
                preparedEnterRequest = enterRequest
                recordStage("prepared")
                returnToTop = async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        if (!wasAtTop) scrollListToAbsoluteTop()
                        recordStage("return_to_top_finished")
                        Result.success(Unit)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Timber.w(error, "New-memo return-to-top failed")
                        Result.failure(error)
                    }
                }
                val accepted = CompletableDeferred<String>()
                val write = async(start = CoroutineStart.UNDISPATCHED) {
                    createMemo(request, wasAtTop) { memoId -> accepted.complete(memoId) }
                        .also { recordStage("write_returned") }
                }
                val memoId =
                    select {
                        accepted.onAwait { it }
                        write.onAwait { it }
                    }
                if (memoId == null) {
                    return@launch
                }
                recordStage("accepted")
                presentation = async(start = CoroutineStart.UNDISPATCHED) {
                    present(enterRequest, memoId, returnToTop, ::recordStage) {
                        enteredMemoId = memoId
                    }
                }
                val committedId = write.await()
                if (committedId == null) {
                    // The engine withdrew the accepted row: nothing may stay held or entering.
                    presentation.cancelAndJoin()
                } else {
                    check(committedId == memoId) { "A create must commit its accepted identity" }
                    durablyCommitted = true
                    preparedEnterResolved = presentation.await()
                }
            } finally {
                presentation?.cancel()
                returnToTop?.cancel()
                if (!durablyCommitted) {
                    enteredMemoId?.let(releaseEnter)
                }
                if (!preparedEnterResolved) {
                    preparedEnterRequest?.let(cancelPreparedEnter)
                }
                submissionInFlight = false
                recordStage("finished")
            }
        }
        return true
    }

    /** Binds, ranks and reveals the accepted row; true once its enter started. */
    private suspend fun present(
        enterRequest: EnterRequestId,
        memoId: String,
        returnToTop: Deferred<Result<Unit>>,
        recordStage: (String) -> Unit,
        onEnterStarted: () -> Unit,
    ): Boolean = try {
        bindCommittedEnter(enterRequest, memoId)
        val rank = createdMemoRank(memoId)
        recordStage("rank_returned")
        coroutineScope {
            val itemReady = rank?.let {
                require(it >= 0) { "Created memo rank must be non-negative" }
                // Supply the exact paging hint while return-to-top is still running.
                async(start = CoroutineStart.UNDISPATCHED) {
                    val ready = awaitCreatedItem(memoId, it)
                    recordStage(if (ready) "page_ready" else "page_unavailable")
                    ready
                }
            }
            returnToTop.await().getOrThrow()
            if (rank != null && itemReady?.await() == true && revealCreatedItem(memoId, rank)) {
                recordStage("viewport_ready")
                startPreparedEnter(enterRequest)
                onEnterStarted()
                recordStage("enter_started")
                true
            } else {
                false
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // behavior-contract: silent-result-ok: a failed optional reveal is logged and its
        // hold released; the editor still reports the durable save outcome independently.
        // NewMemoCreationOracleContractTest covers failure, cancellation and retry.
        Timber.w(error, "New-memo reveal degraded: presentation step failed after acceptance")
        false
    }
}
