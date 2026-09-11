package com.lomo.data.engine

import com.lomo.domain.model.EngineCommandFailure
import com.lomo.domain.model.EngineCommandFailureException
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineRetryDisposition

/**
 * Single conversion edge for engine failures.
 *
 * Everything Rust refuses arrives here as a typed [com.lomo.nativebridge.EngineFailure]; the
 * generated [com.lomo.nativebridge.EngineError] carrier itself has no message, so any code path that
 * lets it escape reduces a structured rejection to a blank one. Data converts once, at the boundary,
 * and only [EngineCommandFailureException] leaves this module.
 */
internal fun com.lomo.nativebridge.EngineFailure.toEngineCommandFailure(): EngineCommandFailure {
    val category = EngineFailureCategory.fromWireOrNull(category)
    val retryDisposition = EngineRetryDisposition.fromWireOrNull(retryDisposition)
    if (category != null && retryDisposition != null) {
        return EngineCommandFailure(
            category = category,
            code = code,
            retryDisposition = retryDisposition,
            operationId = operationId,
            jobId = jobId,
            diagnostic = diagnostic,
        )
    }
    // An unknown token means these bindings and the kernel disagree, which is an internal defect of
    // this build. Classifying it as internal is truthful; the raw tokens and the original diagnostic
    // are preserved so the actual rejection is never replaced by a parse failure.
    return EngineCommandFailure(
        category = EngineFailureCategory.INTERNAL,
        code = code,
        retryDisposition = EngineRetryDisposition.NEVER,
        operationId = operationId,
        jobId = jobId,
        diagnostic =
            "$diagnostic (unrecognized engine vocabulary: category=${this.category}, " +
                "retry_disposition=${this.retryDisposition})",
    )
}

/** Converts one platform-neutral engine failure snapshot into the shared typed carrier. */
internal fun EngineFailureSnapshot.toEngineCommandFailure(
    operationId: String? = null,
    jobId: String? = null,
): EngineCommandFailure =
    com.lomo.nativebridge
        .EngineFailure(
            category = category,
            code = code,
            retryDisposition = retryDisposition,
            operationId = operationId,
            jobId = jobId,
            diagnostic = diagnostic,
        ).toEngineCommandFailure()

/** Builds a typed rejection for a precondition the platform boundary itself refuses. */
internal fun engineCommandFailure(
    category: EngineFailureCategory,
    code: String,
    retryDisposition: EngineRetryDisposition,
    diagnostic: String,
    operationId: String? = null,
    jobId: String? = null,
): EngineCommandFailureException =
    EngineCommandFailureException(
        EngineCommandFailure(
            category = category,
            code = code,
            retryDisposition = retryDisposition,
            operationId = operationId,
            jobId = jobId,
            diagnostic = diagnostic,
        ),
    )

/**
 * Runs one native call and converts its rejection.
 *
 * [com.lomo.nativebridge.EngineError] is a sealed carrier without a message; catching it here is the
 * only place that knows how to keep its typed payload.
 */
internal inline fun <T> withEngineFailureConversion(block: () -> T): T =
    try {
        block()
    } catch (error: com.lomo.nativebridge.EngineError.Failure) {
        throw EngineCommandFailureException(error.failure.toEngineCommandFailure(), cause = error)
    }
