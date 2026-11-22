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

    /** Host-stamped epoch milliseconds when the event was recorded, or 0 if unstamped. */
    val recordedAtMillis: Long

    data class Committed(
        override val label: String,
        override val durationMillis: Long,
        val coreRevision: Long,
        val scopes: List<String>,
        override val recordedAtMillis: Long = 0L,
    ) : EngineDiagnosticEvent

    data class Rejected(
        override val label: String,
        override val durationMillis: Long?,
        val failure: EngineCommandFailure,
        val causeSummary: String = "",
        override val recordedAtMillis: Long = 0L,
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
        override val recordedAtMillis: Long = 0L,
    ) : EngineDiagnosticEvent
}

/** True when this event represents an error or timeout rather than a committed write/read. */
val EngineDiagnosticEvent.isError: Boolean
    get() = this is EngineDiagnosticEvent.Rejected || this is EngineDiagnosticEvent.Stalled

/**
 * Bounded, in-memory diagnostics channel.
 *
 * Not persistence and not telemetry: nothing leaves the device and nothing survives process death.
 */
interface EngineDiagnosticsRecorder {
    /** Newest event first, capped at a bounded window. */
    val events: StateFlow<List<EngineDiagnosticEvent>>

    fun record(event: EngineDiagnosticEvent)

    fun clear()
}

/**
 * Structured diagnostics for the on-device window, clipboard and log.
 * Keep free-form diagnostics and correlation IDs out of the shared summary representation.
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

/**
 * Returns redacted, share-safe exception/detail lines for a diagnostic event, stripping paths,
 * SAF document IDs, URL credentials, and secret tokens while keeping error causes debuggable.
 */
fun EngineDiagnosticEvent.redactedExtraLines(): List<String> = when (this) {
    is EngineDiagnosticEvent.Committed,
    is EngineDiagnosticEvent.Stalled,
    -> emptyList()

    is EngineDiagnosticEvent.Rejected ->
        buildList {
            sanitizeDiagnosticText(failure.diagnostic)
                .takeIf(String::isNotEmpty)
                ?.let { sanitized -> add("detail=$sanitized") }
            sanitizeDiagnosticText(causeSummary)
                .takeIf(String::isNotEmpty)
                ?.let { sanitized -> add("cause=$sanitized") }
        }
}

fun sanitizeDiagnosticText(raw: String, maxChars: Int = DEFAULT_DIAGNOSTIC_MAX_CHARS): String {
    val collapsed = raw.replace(WHITESPACE_RUN_REGEX, " ").trim()
    val scrubbed =
        collapsed
            .let { SECRET_PAIR_REGEX.replace(it, "$1=<redacted>") }
            .let { BEARER_TOKEN_REGEX.replace(it, "Bearer <redacted>") }
            .let { URL_USERINFO_REGEX.replace(it, "$1<redacted>@") }
            .let { CONTENT_URI_REGEX.replace(it, "content://$1/<redacted>") }
            .let { FILESYSTEM_PATH_REGEX.replace(it, "<path>") }
            .trim()
    return if (scrubbed.length > maxChars && maxChars > 1) {
        "${scrubbed.take(maxChars - 1)}…"
    } else {
        scrubbed.take(maxChars.coerceAtLeast(0))
    }
}

private const val DEFAULT_DIAGNOSTIC_MAX_CHARS = 240
private val WHITESPACE_RUN_REGEX = Regex("""[\r\n\t ]+""")
private val SECRET_PAIR_REGEX = Regex(
    """(?i)\b(password|passwd|secret|token|authorization|accesskey|apikey|pat)\s*([:=])\s*\S+""",
)
private val BEARER_TOKEN_REGEX = Regex("""(?i)\bBearer\s+\S+""")
private val URL_USERINFO_REGEX = Regex("""(https?://)[^/\s@]+@""")
private val CONTENT_URI_REGEX = Regex("""content://([^/\s]+)/\S+""")
private val FILESYSTEM_PATH_REGEX = Regex("""(?:/(?:storage|data|sdcard|mnt|Users|home)/\S+|[A-Za-z]:\\\S+)""")

private val diagnosticIdentifierPattern = Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,127}")

private fun String.diagnosticIdentifier(): String = if (matches(
        diagnosticIdentifierPattern,
    )
) {
    this
} else {
    "invalid_identifier"
}
