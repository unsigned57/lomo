package com.lomo.data.engine

import com.lomo.domain.model.EngineReadiness

internal fun recoveryFromThrowable(error: Throwable): EngineReadiness.ReadOnlyRecovery =
    when (error) {
        is WorkspaceActivationException -> error.recovery
        is com.lomo.nativebridge.EngineError.Failure ->
            EngineReadiness.ReadOnlyRecovery(
                category = error.failure.category.toFailureCategory(),
                code = error.failure.code,
                retryDisposition = error.failure.retryDisposition.toRecoveryRetryDisposition(),
                diagnostic = error.failure.diagnostic,
            )
        else ->
            EngineReadiness.ReadOnlyRecovery(
                category = EngineReadiness.FailureCategory.INTERNAL,
                code = "workspace_open_failed",
                retryDisposition = EngineReadiness.RetryDisposition.AFTER_USER_ACTION,
                diagnostic = error.message ?: "Workspace open failed",
            )
    }

private fun String.toRecoveryRetryDisposition(): EngineReadiness.RetryDisposition =
    when (this) {
        "never" -> EngineReadiness.RetryDisposition.NEVER
        "after_user_action" -> EngineReadiness.RetryDisposition.AFTER_USER_ACTION
        "transient" -> EngineReadiness.RetryDisposition.TRANSIENT
        else -> error("Unknown Rust engine retry disposition: $this")
    }

internal fun workspaceOpenNotReady(readiness: EngineReadiness): EngineReadiness.ReadOnlyRecovery =
    EngineReadiness.ReadOnlyRecovery(
        category = EngineReadiness.FailureCategory.INTERNAL,
        code = "workspace_open_not_ready",
        retryDisposition = EngineReadiness.RetryDisposition.AFTER_USER_ACTION,
        diagnostic = "Workspace open did not reach Ready (${readiness::class.simpleName})",
    )
