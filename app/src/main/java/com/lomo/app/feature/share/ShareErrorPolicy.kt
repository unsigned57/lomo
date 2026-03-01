package com.lomo.app.feature.share

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShareErrorPolicy
    @Inject
    constructor() {
        fun sanitizeUserFacingMessage(
            rawMessage: String?,
            fallbackMessage: String,
        ): String {
            val message = rawMessage?.trim().orEmpty()
            if (message.isBlank()) return fallbackMessage
            if (isTechnicalMessage(message)) return fallbackMessage
            return message
        }

        fun isTechnicalMessage(message: String): Boolean {
            val detail = message.trim()
            if (detail.isBlank()) return true
            return detail.length > 200 ||
                detail.contains('\n') ||
                detail.contains('\r') ||
                detail.contains("exception", ignoreCase = true) ||
                detail.contains("java.", ignoreCase = true) ||
                detail.contains("kotlin.", ignoreCase = true) ||
                detail.contains("stacktrace", ignoreCase = true) ||
                detail.contains("\tat")
        }
    }
