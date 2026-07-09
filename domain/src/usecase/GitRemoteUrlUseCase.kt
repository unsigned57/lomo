package com.lomo.domain.usecase

class GitRemoteUrlUseCase {
        fun normalize(url: String): String = url.trim().removeSuffix("/")

        fun isValid(url: String): Boolean {
            val trimmed = url.trim()
            if (trimmed.isBlank()) return true
            return trimmed.startsWith(HTTPS_PREFIX) &&
                trimmed.count { it == PATH_SEPARATOR } >= MIN_REMOTE_URL_SLASH_COUNT
        }

        private companion object {
            private const val HTTPS_PREFIX = "https://"
            private const val PATH_SEPARATOR = '/'
            private const val MIN_REMOTE_URL_SLASH_COUNT = 3
        }
}
