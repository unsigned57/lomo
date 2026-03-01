package com.lomo.domain.usecase

class GitRemoteUrlUseCase
    constructor() {
        fun normalize(url: String): String = url.trim().removeSuffix("/")

        fun isValid(url: String): Boolean {
            val trimmed = url.trim()
            if (trimmed.isBlank()) return true
            return trimmed.startsWith("https://") && trimmed.count { it == '/' } >= 3
        }
    }
