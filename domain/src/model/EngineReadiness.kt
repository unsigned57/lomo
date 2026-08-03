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

/** Coarse workspace fact safe for a user-shareable recovery report. */
enum class RecoveryWorkspaceKind {
    NONE,
    DIRECT,
    SAF,
}

/**
 * Bounded diagnostic artifact that deliberately excludes the untrusted native diagnostic string.
 *
 * The raw diagnostic may contain a provider response, a workspace path, memo text, or capability
 * token. Exporting it would turn a recovery feature into a data-exfiltration path. The report keeps
 * only typed facts whose values are constrained at construction.
 */
data class RecoveryDiagnosticReport(
    val schemaVersion: Int,
    val fileName: String,
    val content: String,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported recovery diagnostic schema" }
        require(fileName == FILE_NAME) { "Recovery diagnostic filename must be canonical" }
        require(content.encodeToByteArray().size <= MAX_BYTES) {
            "Recovery diagnostic report exceeds $MAX_BYTES bytes"
        }
    }

    companion object {
        const val SCHEMA_VERSION: Int = 1
        const val FILE_NAME: String = "lomo-recovery-diagnostic-v1.txt"
        const val MAX_BYTES: Int = 4096
    }
}

/** Observable result of rebuilding only the disposable Rust SQLite projection. */
data class DerivedIndexRebuildSummary(
    val memosIndexed: ULong,
    val fileCount: ULong,
    val attachmentCount: ULong,
    val corruptLomoIsolated: ULong,
    val highWaterRevision: ULong,
)

/** Only known rebuildable SQLite failures may expose the destructive-index recovery action. */
fun EngineReadiness.ReadOnlyRecovery.canRebuildDerivedIndex(): Boolean =
    code == "sqlite_integrity_failed" || code == "sqlite_error"

/** Builds the canonical secret-free report for a read-only recovery state. */
fun EngineReadiness.ReadOnlyRecovery.toDiagnosticReport(
    workspaceKind: RecoveryWorkspaceKind,
): RecoveryDiagnosticReport {
    require(code.matches(Regex("[a-z][a-z0-9_.-]{0,127}"))) {
        "Recovery error code must be a bounded canonical identifier"
    }
    val category =
        when (category) {
            EngineReadiness.FailureCategory.VALIDATION -> "validation"
            EngineReadiness.FailureCategory.PERMISSION -> "permission"
            EngineReadiness.FailureCategory.CORRUPTION -> "corruption"
            EngineReadiness.FailureCategory.STORAGE -> "storage"
            EngineReadiness.FailureCategory.NETWORK -> "network"
            EngineReadiness.FailureCategory.AUTHENTICATION -> "authentication"
            EngineReadiness.FailureCategory.CONFLICT -> "conflict"
            EngineReadiness.FailureCategory.CANCELLED -> "cancelled"
            EngineReadiness.FailureCategory.TIMEOUT -> "timeout"
            EngineReadiness.FailureCategory.BUSY -> "busy"
            EngineReadiness.FailureCategory.RESOURCE_LIMIT -> "resource_limit"
            EngineReadiness.FailureCategory.INTERNAL -> "internal"
        }
    val retry =
        when (retryDisposition) {
            EngineReadiness.RetryDisposition.NEVER -> "never"
            EngineReadiness.RetryDisposition.AFTER_USER_ACTION -> "after_user_action"
            EngineReadiness.RetryDisposition.TRANSIENT -> "transient"
        }
    val workspace =
        when (workspaceKind) {
            RecoveryWorkspaceKind.NONE -> "none"
            RecoveryWorkspaceKind.DIRECT -> "direct"
            RecoveryWorkspaceKind.SAF -> "saf"
        }
    return RecoveryDiagnosticReport(
        schemaVersion = RecoveryDiagnosticReport.SCHEMA_VERSION,
        fileName = RecoveryDiagnosticReport.FILE_NAME,
        content =
            buildString {
                appendLine("lomo_recovery_diagnostic_v1")
                appendLine("category=$category")
                appendLine("code=$code")
                appendLine("retry=$retry")
                appendLine("workspace_kind=$workspace")
            },
    )
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
