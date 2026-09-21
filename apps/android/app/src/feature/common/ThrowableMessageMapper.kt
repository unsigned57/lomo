package com.lomo.app.feature.common

import com.lomo.domain.model.EngineCommandFailureException

/**
 * Renders one failure for the user.
 *
 * A typed engine rejection is rendered as its stable code: the code names which rule refused and is
 * safe to display, while the raw diagnostic may quote workspace paths or memo text and therefore
 * belongs only in the diagnostics channel.
 */
internal fun Throwable.toUserMessage(
    prefix: String? = null,
    sanitizer: ((rawMessage: String?, fallbackMessage: String) -> String)? = null,
): String {
    val fallback = prefix?.trim().orEmpty().ifBlank { null }
    val engineCode = (this as? EngineCommandFailureException)?.run { failure.code }
    if (engineCode != null) {
        return if (fallback == null) engineCode else "$fallback: $engineCode"
    }
    val sanitized =
        if (fallback != null && sanitizer != null) {
            sanitizer(message, fallback).trim().ifBlank { fallback }
        } else {
            null
        }

    return sanitized
        ?: when {
            fallback == null && message.isNullOrBlank() -> "Unexpected error"
            fallback == null -> message.orEmpty()
            message.isNullOrBlank() -> fallback
            else -> "$fallback: $message"
        }
}
