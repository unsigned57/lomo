package com.lomo.domain.model

import kotlinx.coroutines.flow.StateFlow

/**
 * One recorded outcome of a Lomo operation.
 *
 * The channel exists because a rejection that only becomes a user-facing sentence is unusable for
 * diagnosis: the code, category and timing are what identify which rule refused.
 */
sealed interface EngineDiagnosticEvent {
    /** Stable operation label, e.g. `memo.delete`. */
    val label: String

    /** Wall-clock duration of the observed operation. */
    val durationMillis: Long?

    data class Committed(
        override val label: String,
        override val durationMillis: Long,
        val coreRevision: Long,
        val scopes: List<String>,
    ) : EngineDiagnosticEvent

    data class Rejected(
        override val label: String,
        override val durationMillis: Long?,
        val failure: EngineCommandFailure,
    ) : EngineDiagnosticEvent

    /**
     * A submission that has stayed in flight past its acknowledgement budget.
     *
     * This is the only observable trace of a chain that produced no terminal state at all, which is
     * exactly how an editor can stay open forever without any exception being thrown.
     */
    data class Stalled(override val label: String, override val durationMillis: Long) : EngineDiagnosticEvent
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
 * Structured diagnostics for the on-device window, clipboard and log.
 * Keep free-form diagnostics and correlation IDs out of the shared representation.
 */
private fun EngineDiagnosticEvent.detail(): String = when (this) {
    is EngineDiagnosticEvent.Committed ->
        "committed · revision=$coreRevision · scopes=${scopes.joinToString(
            ",",
        ) { it.diagnosticIdentifier() }.ifEmpty { "-" }}"

    is EngineDiagnosticEvent.Rejected ->
        buildString {
            append("rejected · ${failure.code.diagnosticIdentifier()} · ${failure.category.wireValue}")
            append(" · retry=${failure.retryDisposition.wireValue}")
            failure.workspaceStage?.let { append(" · stage=${it.wireValue}") }
        }

    is EngineDiagnosticEvent.Stalled ->
        "stalled · no terminal acknowledgement within the submission budget"
}

fun EngineDiagnosticEvent.summary(): String = buildString {
    append(label.diagnosticIdentifier())
    durationMillis?.let { append(" · ${it}ms") }
    append(" · ")
    append(detail())
}

private val diagnosticIdentifierPattern = Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,127}")

private fun String.diagnosticIdentifier(): String = if (matches(
        diagnosticIdentifierPattern,
    )
) {
    this
} else {
    "invalid_identifier"
}
