package com.lomo.domain.usecase

class GitSyncErrorUseCase
    constructor() {
        enum class ErrorKind {
            CONFLICT,
            DIRECT_PATH_REQUIRED,
            TECHNICAL,
            USER_FACING,
            EMPTY,
        }

        fun sanitizeUserFacingMessage(
            rawMessage: String?,
            fallbackMessage: String,
        ): String {
            val message = rawMessage?.trim().orEmpty()
            return when (classify(message)) {
                ErrorKind.CONFLICT,
                ErrorKind.DIRECT_PATH_REQUIRED,
                ErrorKind.USER_FACING,
                -> message
                ErrorKind.TECHNICAL,
                ErrorKind.EMPTY,
                -> fallbackMessage
            }
        }

        fun classify(message: String?): ErrorKind {
            val normalized = message?.trim().orEmpty()
            if (normalized.isBlank()) return ErrorKind.EMPTY
            if (isConflictMessage(normalized)) return ErrorKind.CONFLICT
            if (normalized.startsWith(DIRECT_PATH_REQUIRED_PREFIX, ignoreCase = true)) return ErrorKind.DIRECT_PATH_REQUIRED
            if (looksTechnicalMessage(normalized)) return ErrorKind.TECHNICAL
            return ErrorKind.USER_FACING
        }

        fun isConflictMessage(message: String): Boolean =
            message.contains("rebase STOPPED", ignoreCase = true) ||
                message.contains("resolve conflicts manually", ignoreCase = true) ||
                (message.contains("rebase", ignoreCase = true) &&
                    message.contains("preserved", ignoreCase = true))

        fun looksTechnicalMessage(message: String): Boolean =
            message.length > 200 ||
                message.contains('\n') ||
                message.contains('\r') ||
                message.contains("exception", ignoreCase = true) ||
                message.contains("java.", ignoreCase = true) ||
                message.contains("kotlin.", ignoreCase = true) ||
                message.contains("stacktrace", ignoreCase = true) ||
                message.contains("\tat")

        private companion object {
            private const val DIRECT_PATH_REQUIRED_PREFIX = "Git sync requires direct path mode"
        }
    }
