package com.lomo.app.feature.memo

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lomo.domain.model.Memo
import com.lomo.ui.component.input.InputSheetOwnerSubmission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

@JvmInline
value class MemoEditorSubmissionId(
    val value: Long,
) {
    init {
        require(value > 0L) { "Memo editor submission id must be positive" }
    }
}

enum class MemoEditorSubmissionStatus {
    Idle,
    Submitting,
    Failed,
}

/** Owns the editor-local submission id and accepts only its matching terminal acknowledgement. */
@Stable
internal class MemoEditorSubmissionGate(
    private val onCommitted: () -> Unit,
) {
    var status by mutableStateOf(MemoEditorSubmissionStatus.Idle)
        private set

    private var activeSubmissionId: MemoEditorSubmissionId? = null

    val canDismiss: Boolean
        get() = status != MemoEditorSubmissionStatus.Submitting

    /**
     * This gate's status as the presentation-facing fate of an in-flight sheet submission.
     *
     * [MemoEditorSubmissionStatus.Idle] maps to [InputSheetOwnerSubmission.Resolved] because the
     * sheet only consults this while it still holds a lock: an idle owner then means the
     * submission it is waiting on already committed and reset this gate.
     */
    val ownerSubmission: InputSheetOwnerSubmission
        get() =
            when (status) {
                MemoEditorSubmissionStatus.Submitting -> InputSheetOwnerSubmission.Pending
                MemoEditorSubmissionStatus.Failed -> InputSheetOwnerSubmission.Rejected
                MemoEditorSubmissionStatus.Idle -> InputSheetOwnerSubmission.Resolved
            }

    fun begin(): MemoEditorSubmissionId? {
        if (status == MemoEditorSubmissionStatus.Submitting) return null
        val rawId = nextSubmissionId.getAndIncrement()
        check(rawId > 0L) { "Memo editor submission id space is exhausted" }
        return MemoEditorSubmissionId(rawId).also { submissionId ->
            activeSubmissionId = submissionId
            status = MemoEditorSubmissionStatus.Submitting
        }
    }

    fun fail(submissionId: MemoEditorSubmissionId) {
        if (activeSubmissionId == submissionId && status == MemoEditorSubmissionStatus.Submitting) {
            status = MemoEditorSubmissionStatus.Failed
        }
    }

    fun commit(submissionId: MemoEditorSubmissionId): Boolean {
        if (activeSubmissionId != submissionId || status != MemoEditorSubmissionStatus.Submitting) {
            return false
        }
        reset()
        onCommitted()
        return true
    }

    /**
     * Projects the owning submission state onto this editor lock.
     *
     * The lock must be rebuildable from the owner, because the owner outlives every composition
     * that observes it. Without this projection the only terminal entry point is a value returned
     * through composition, so a cancelled acknowledgement leaves the editor locked forever.
     */
    fun onOwnerState(state: MemoEditorSubmissionState) {
        val active = activeSubmissionId ?: return
        when (state) {
            is MemoEditorSubmissionState.Committed -> if (state.submissionId == active) commit(active)
            is MemoEditorSubmissionState.Failed -> if (state.submissionId == active) fail(active)
            MemoEditorSubmissionState.Idle,
            is MemoEditorSubmissionState.Submitting,
            -> Unit
        }
    }

    fun reset() {
        activeSubmissionId = null
        status = MemoEditorSubmissionStatus.Idle
    }

    private companion object {
        val nextSubmissionId = AtomicLong(1L)
    }
}

sealed interface MemoEditorSubmissionState {
    data object Idle : MemoEditorSubmissionState

    data class Submitting(
        val submissionId: MemoEditorSubmissionId,
    ) : MemoEditorSubmissionState

    data class Committed(
        val submissionId: MemoEditorSubmissionId,
    ) : MemoEditorSubmissionState

    data class Failed(
        val submissionId: MemoEditorSubmissionId,
    ) : MemoEditorSubmissionState
}

/** Runs editor mutations in their ViewModel scope and publishes one acknowledged terminal state. */
internal class MemoEditorSubmissionStateMachine {
    private val transitionLock = Any()
    private val _state = MutableStateFlow<MemoEditorSubmissionState>(MemoEditorSubmissionState.Idle)
    val state: StateFlow<MemoEditorSubmissionState> = _state.asStateFlow()

    fun launch(
        scope: CoroutineScope,
        submissionId: MemoEditorSubmissionId,
        onFailure: (Exception) -> Unit,
        block: suspend () -> Unit,
    ) {
        val shouldLaunch =
            synchronized(transitionLock) {
                when (val current = _state.value) {
                    is MemoEditorSubmissionState.Submitting -> {
                        check(current.submissionId == submissionId) {
                            "A different memo editor submission is already in flight"
                        }
                        false
                    }
                    is MemoEditorSubmissionState.Committed -> current.submissionId != submissionId
                    is MemoEditorSubmissionState.Failed -> current.submissionId != submissionId
                    MemoEditorSubmissionState.Idle -> true
                }.also { launch ->
                    if (launch) {
                        _state.value = MemoEditorSubmissionState.Submitting(submissionId)
                    }
                }
            }
        if (!shouldLaunch) return

        scope.launch {
            try {
                block()
                transitionTerminal(
                    submissionId = submissionId,
                    terminal = MemoEditorSubmissionState.Committed(submissionId),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                onFailure(failure)
                transitionTerminal(
                    submissionId = submissionId,
                    terminal = MemoEditorSubmissionState.Failed(submissionId),
                )
            }
        }
    }

    fun reject(
        submissionId: MemoEditorSubmissionId,
        failure: Exception,
        onFailure: (Exception) -> Unit,
    ) {
        synchronized(transitionLock) {
            val current = _state.value
            check(current !is MemoEditorSubmissionState.Submitting || current.submissionId == submissionId) {
                "Cannot reject a different memo editor submission while one is in flight"
            }
            onFailure(failure)
            _state.value = MemoEditorSubmissionState.Failed(submissionId)
        }
    }

    suspend fun await(submissionId: MemoEditorSubmissionId): Boolean =
        state
            .filter { candidate ->
                when (candidate) {
                    is MemoEditorSubmissionState.Committed -> candidate.submissionId == submissionId
                    is MemoEditorSubmissionState.Failed -> candidate.submissionId == submissionId
                    MemoEditorSubmissionState.Idle,
                    is MemoEditorSubmissionState.Submitting,
                    -> false
                }
            }.first() is MemoEditorSubmissionState.Committed

    private fun transitionTerminal(
        submissionId: MemoEditorSubmissionId,
        terminal: MemoEditorSubmissionState,
    ) {
        synchronized(transitionLock) {
            val current = _state.value
            if (current is MemoEditorSubmissionState.Submitting && current.submissionId == submissionId) {
                _state.value = terminal
            }
        }
    }
}

/** Executes create/update mutations and exposes the exact durable acknowledgement to editor hosts. */
internal class MemoEditorCommitCoordinator(
    private val scope: CoroutineScope,
    private val createMemo: suspend (content: String, geoLocation: String?, timestampMillis: Long?) -> Unit,
    private val updateMemo: suspend (memo: Memo, newContent: String) -> Unit,
    private val onCreateCommitted: () -> Unit,
    private val onUpdateCommitted: () -> Unit,
    private val onStarted: () -> Unit,
    private val onFailure: (Exception) -> Unit,
) {
    private val stateMachine = MemoEditorSubmissionStateMachine()
    val state: StateFlow<MemoEditorSubmissionState> = stateMachine.state

    fun create(
        submissionId: MemoEditorSubmissionId,
        content: String,
        geoLocation: String? = null,
        timestampMillis: Long? = null,
    ) {
        onStarted()
        stateMachine.launch(scope, submissionId, onFailure) {
            createMemo(content, geoLocation, timestampMillis)
            onCreateCommitted()
        }
    }

    fun update(
        submissionId: MemoEditorSubmissionId,
        memo: Memo,
        newContent: String,
    ) {
        onStarted()
        stateMachine.launch(scope, submissionId, onFailure) {
            updateMemo(memo, newContent)
            onUpdateCommitted()
        }
    }

    suspend fun await(submissionId: MemoEditorSubmissionId): Boolean = stateMachine.await(submissionId)

    fun reject(
        submissionId: MemoEditorSubmissionId,
        failure: Exception,
    ) {
        stateMachine.reject(submissionId, failure, onFailure)
    }
}

/** Adapts one screen-owned memo projection update to the shared submission acknowledgement law. */
internal class MemoEditorUpdateSubmission(
    private val scope: CoroutineScope,
    private val updateMemo: suspend (memo: Memo, newContent: String) -> Unit,
    private val onFailure: (Exception) -> Unit,
) {
    private val stateMachine = MemoEditorSubmissionStateMachine()
    val state: StateFlow<MemoEditorSubmissionState> = stateMachine.state

    suspend fun submit(
        submissionId: MemoEditorSubmissionId,
        memo: Memo,
        newContent: String,
    ): Boolean {
        stateMachine.launch(scope, submissionId, onFailure) {
            updateMemo(memo, newContent)
        }
        return stateMachine.await(submissionId)
    }
}
