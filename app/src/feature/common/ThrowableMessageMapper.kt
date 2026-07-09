package com.lomo.app.feature.common

internal fun Throwable.toUserMessage(
    prefix: String? = null,
    sanitizer: ((rawMessage: String?, fallbackMessage: String) -> String)? = null,
): String {
    val fallback = prefix?.trim().orEmpty().ifBlank { null }
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
