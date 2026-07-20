package com.lomo.domain.model

/**
 * Platform-neutral write authority published by the Rust application kernel.
 *
 * Only [Ready] permits workspace writes. Every other state is intentionally distinct; callers
 * must not collapse missing selection, bootstrap, recovery, or shutdown into an empty/default
 * workspace.
 */
sealed interface EngineReadiness {
    data object AwaitingWorkspaceSelection : EngineReadiness

    data object Opening : EngineReadiness

    data class Ready(
        val coreRevision: ULong,
        val eventSequence: ULong,
    ) : EngineReadiness

    data class ReadOnlyRecovery(
        val category: FailureCategory,
        val code: String,
        val retryDisposition: RetryDisposition,
        val diagnostic: String,
    ) : EngineReadiness

    data object ShuttingDown : EngineReadiness

    enum class FailureCategory {
        VALIDATION,
        PERMISSION,
        CORRUPTION,
        STORAGE,
        NETWORK,
        AUTHENTICATION,
        CONFLICT,
        CANCELLED,
        TIMEOUT,
        BUSY,
        RESOURCE_LIMIT,
        INTERNAL,
    }

    enum class RetryDisposition {
        NEVER,
        AFTER_USER_ACTION,
        TRANSIENT,
    }
}

/**
 * Stage-1 global write hard gate.
 *
 * Only [EngineReadiness.Ready] is writable, and only when [writeFrozen] is false. Missing selection,
 * bootstrap, recovery, shutdown, and in-flight workspace switch all fail closed with a structured
 * [IllegalStateException] rather than falling back to the old Kotlin core.
 */
fun EngineReadiness.requireWritable(writeFrozen: Boolean = false) {
    if (writeFrozen) {
        throw IllegalStateException("Workspace switch is in progress; writes are frozen")
    }
    val blockedReason =
        when (this) {
            is EngineReadiness.Ready -> return
            EngineReadiness.AwaitingWorkspaceSelection ->
                "Engine is awaiting workspace selection; writes are blocked"
            EngineReadiness.Opening ->
                "Engine is opening; writes are blocked until Ready"
            is EngineReadiness.ReadOnlyRecovery ->
                "Engine is in read-only recovery ($code): $diagnostic"
            EngineReadiness.ShuttingDown ->
                "Engine is shutting down; writes are blocked"
        }
    throw IllegalStateException(blockedReason)
}

/** True only when the Rust engine has published Ready and no workspace switch freeze is active. */
fun EngineReadiness.isWritable(writeFrozen: Boolean = false): Boolean =
    this is EngineReadiness.Ready && !writeFrozen
