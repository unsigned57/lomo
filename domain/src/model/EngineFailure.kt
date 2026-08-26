package com.lomo.domain.model

/**
 * Stable failure category vocabulary published by the Rust kernel.
 *
 * The same twelve tokens cross every boundary (engine readiness, job failures, memo command
 * rejections), so they are modeled once here instead of once per consumer.
 */
enum class EngineFailureCategory(
    val wireValue: String,
) {
    VALIDATION("validation"),
    PERMISSION("permission"),
    CORRUPTION("corruption"),
    STORAGE("storage"),
    NETWORK("network"),
    AUTHENTICATION("authentication"),
    CONFLICT("conflict"),
    CANCELLED("cancelled"),
    TIMEOUT("timeout"),
    BUSY("busy"),
    RESOURCE_LIMIT("resource_limit"),
    INTERNAL("internal"),
    ;

    companion object {
        /**
         * Parses one wire token, or null when this build does not know it.
         *
         * Null is not a default: callers decide whether an unknown token must fail closed (readiness)
         * or must be preserved alongside the original failure (command rejections).
         */
        fun fromWireOrNull(value: String): EngineFailureCategory? = entries.firstOrNull { it.wireValue == value }
    }
}

/** Stable retry vocabulary published by the Rust kernel alongside [EngineFailureCategory]. */
enum class EngineRetryDisposition(
    val wireValue: String,
) {
    NEVER("never"),
    AFTER_USER_ACTION("after_user_action"),
    TRANSIENT("transient"),
    ;

    companion object {
        /** Parses one wire token, or null when this build does not know it. */
        fun fromWireOrNull(value: String): EngineRetryDisposition? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * One structured engine rejection.
 *
 * Rust refuses an operation with a category, a stable code, a retry disposition and a diagnostic.
 * This carrier keeps all four across the FFI, repository and presentation boundaries so that no
 * layer has to reduce a rejection to a free-text message — the reduction that made every store
 * command failure indistinguishable at the UI.
 */
data class EngineCommandFailure(
    val category: EngineFailureCategory,
    val code: String,
    val retryDisposition: EngineRetryDisposition,
    val operationId: String?,
    val jobId: String?,
    val diagnostic: String,
) {
    init {
        require(code.isNotBlank()) { "Engine failure code must not be blank" }
    }

    /** Never blank: the code alone identifies the rejection when Rust published no diagnostic. */
    fun describe(): String = if (diagnostic.isBlank()) code else "$code: $diagnostic"
}

/**
 * Exception carrier for [EngineCommandFailure].
 *
 * Extends [IllegalStateException] so the existing engine-failure catch sites keep their shape, but
 * unlike a bare native exception its message is always non-blank and its typed [failure] survives.
 * [cause] keeps the original native carrier so its stack trace is not lost at the conversion edge.
 */
class EngineCommandFailureException(
    val failure: EngineCommandFailure,
    cause: Throwable? = null,
) : IllegalStateException(failure.describe(), cause)
