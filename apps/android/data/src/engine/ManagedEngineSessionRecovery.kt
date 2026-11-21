package com.lomo.data.engine

import com.lomo.domain.model.EngineCommandFailure
import com.lomo.domain.model.EngineCommandFailureException
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.EngineRetryDisposition

internal fun recoveryFromThrowable(error: Throwable): EngineReadiness.ReadOnlyRecovery = when (error) {
    is CapabilityRegistryException ->
        EngineReadiness.ReadOnlyRecovery(
            category = error.category.toFailureCategory(),
            code = error.code,
            retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
            diagnostic = error.diagnostic,
        )

    is EngineCommandFailureException -> error.failure.toRecovery()

    is com.lomo.nativebridge.EngineError.Failure -> error.failure.toEngineCommandFailure().toRecovery()

    else ->
        EngineReadiness.ReadOnlyRecovery(
            category = EngineFailureCategory.INTERNAL,
            code = "workspace_open_failed",
            retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
            diagnostic = error.message ?: "Workspace open failed",
        )
}

private fun EngineCommandFailure.toRecovery(): EngineReadiness.ReadOnlyRecovery =
    EngineReadiness.ReadOnlyRecovery(category, code, retryDisposition, diagnostic, workspaceStage)

internal fun workspaceOpenRecovery(readiness: EngineReadiness): EngineReadiness.ReadOnlyRecovery =
    readiness as? EngineReadiness.ReadOnlyRecovery ?: EngineReadiness.ReadOnlyRecovery(
        category = EngineFailureCategory.INTERNAL,
        code = "workspace_open_not_ready",
        retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
        diagnostic = "Workspace open did not reach Ready (${readiness::class.simpleName})",
    )
