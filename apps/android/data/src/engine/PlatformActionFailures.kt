package com.lomo.data.engine

import com.lomo.nativebridge.EngineFailure

/**
 * Maps platform-action exception types onto the wire-visible engine failure contract.
 *
 * Split from the action dispatch so each failure keeps its category, code and retry disposition
 * instead of collapsing into a message at the boundary. An absent `EngineFailure.jobId` is the
 * contract for failures that are not owned by a native job.
 */
internal fun DirectRootAccessException.toFailure(): EngineFailure =
    EngineFailure(
        category = category,
        code = code,
        retryDisposition =
            when (category) {
                "conflict", "permission" -> "after_user_action"
                "timeout" -> "transient"
                else -> "never"
            },
        operationId = null,
        jobId = null,
        diagnostic = diagnostic,
    )

internal fun CapabilityRegistryException.toFailure(): EngineFailure =
    EngineFailure(
        category = category,
        code = code,
        retryDisposition = "after_user_action",
        operationId = null,
        jobId = null,
        diagnostic = diagnostic,
    )

internal fun ExchangeResolverException.toFailure(): EngineFailure =
    EngineFailure(
        category = category,
        code = code,
        retryDisposition = "never",
        operationId = null,
        jobId = null,
        diagnostic = diagnostic,
    )
