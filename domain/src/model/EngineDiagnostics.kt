package com.lomo.domain.model

import kotlinx.coroutines.flow.StateFlow

/**
 * One recorded fact about a workspace command or editor submission.
 *
 * The channel exists because a rejection that only becomes a user-facing sentence is unusable for
 * diagnosis: the code, category and timing are what identify which rule refused.
 */
sealed interface EngineDiagnosticEvent {
    /** Stable operation label, e.g. `memo.delete`. */
    val label: String

    /** Wall-clock duration of the observed operation. */
    val durationMillis: Long

    data class Committed(
        override val label: String,
        override val durationMillis: Long,
        val coreRevision: Long,
        val scopes: List<String>,
    ) : EngineDiagnosticEvent

    data class Rejected(
        override val label: String,
        override val durationMillis: Long,
        val failure: EngineCommandFailure,
    ) : EngineDiagnosticEvent

    /**
     * A submission that has stayed in flight past its acknowledgement budget.
     *
     * This is the only observable trace of a chain that produced no terminal state at all, which is
     * exactly how an editor can stay open forever without any exception being thrown.
     */
    data class Stalled(
        override val label: String,
        override val durationMillis: Long,
    ) : EngineDiagnosticEvent
}

/**
 * Bounded, in-memory diagnostics channel.
 *
 * Not persistence and not telemetry: nothing leaves the device and nothing survives process death.
 */
interface EngineDiagnosticsRecorder {
    /** Newest event first, capped at a bounded window. */
    val events: StateFlow<List<EngineDiagnosticEvent>>

    fun record(event: EngineDiagnosticEvent)
}

/**
 * The one rendering of an event's outcome, shared by every surface that shows it.
 *
 * A second rendering would let the on-device window and the external log disagree about the same
 * recorded fact, which is precisely the ambiguity a diagnostics channel exists to remove.
 */
fun EngineDiagnosticEvent.detail(): String =
    when (this) {
        is EngineDiagnosticEvent.Committed ->
            "committed · revision=$coreRevision · scopes=${scopes.joinToString(",").ifEmpty { "-" }}"
        is EngineDiagnosticEvent.Rejected ->
            buildString {
                append("rejected · ${failure.code} · ${failure.category.wireValue}")
                append(" · retry=${failure.retryDisposition.wireValue}")
                failure.operationId?.let { append(" · op=$it") }
                failure.jobId?.let { append(" · job=$it") }
                if (failure.diagnostic.isNotBlank()) {
                    append("\n${failure.diagnostic}")
                }
            }
        is EngineDiagnosticEvent.Stalled ->
            "stalled · no terminal acknowledgement within the submission budget"
    }
