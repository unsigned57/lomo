package com.lomo.data.engine

import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.EngineRetryDisposition

internal fun recoveryFromThrowable(error: Throwable): EngineReadiness.ReadOnlyRecovery =
    when (error) {
        is WorkspaceActivationException -> error.recovery
        is CapabilityRegistryException ->
            EngineReadiness.ReadOnlyRecovery(
                category = error.category.toFailureCategory(),
                code = error.code,
                retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                diagnostic = error.diagnostic,
            )
        is com.lomo.nativebridge.EngineError.Failure ->
            EngineReadiness.ReadOnlyRecovery(
                category = error.failure.category.toFailureCategory(),
                code = error.failure.code,
                retryDisposition = error.failure.retryDisposition.toRecoveryRetryDisposition(),
                diagnostic = error.failure.diagnostic,
            )
        else ->
            EngineReadiness.ReadOnlyRecovery(
                category = EngineFailureCategory.INTERNAL,
                code = "workspace_open_failed",
                retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                diagnostic = error.message ?: "Workspace open failed",
            )
    }

private fun String.toRecoveryRetryDisposition(): EngineRetryDisposition =
    EngineRetryDisposition.fromWireOrNull(this)
        ?: error("Unknown Rust engine retry disposition: $this")

internal fun workspaceOpenNotReady(readiness: EngineReadiness): EngineReadiness.ReadOnlyRecovery =
    EngineReadiness.ReadOnlyRecovery(
        category = EngineFailureCategory.INTERNAL,
        code = "workspace_open_not_ready",
        retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
        diagnostic = "Workspace open did not reach Ready (${readiness::class.simpleName})",
    )
