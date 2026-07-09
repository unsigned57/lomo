package com.lomo.app.feature.share



private const val TECHNICAL_MESSAGE_MAX_LENGTH = 200

class ShareErrorPolicy {
        fun sanitizeUserFacingMessage(
            rawMessage: String?,
            fallbackMessage: String,
        ): String =
            rawMessage
                ?.trim()
                ?.takeUnless(String::isBlank)
                ?.takeUnless(::isTechnicalMessage)
                ?: fallbackMessage

        fun isTechnicalMessage(message: String): Boolean {
            val detail = message.trim()
            if (detail.isBlank()) return true
            return detail.length > TECHNICAL_MESSAGE_MAX_LENGTH ||
                detail.contains('\n') ||
                detail.contains('\r') ||
                detail.contains("exception", ignoreCase = true) ||
                detail.contains("java.", ignoreCase = true) ||
                detail.contains("kotlin.", ignoreCase = true) ||
                detail.contains("stacktrace", ignoreCase = true) ||
                detail.contains("\tat")
        }
    }
