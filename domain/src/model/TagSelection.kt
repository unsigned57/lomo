package com.lomo.domain.model

/** A validated workspace tag path and the explicit matching semantics requested by the caller. */
@JvmInline
value class TagPath(val value: String) {
    private companion object {
        const val MAX_LENGTH = 128
    }

    init {
        require(value.isNotBlank()) { "Tag path must not be blank" }
        require(value.length <= MAX_LENGTH) { "Tag path exceeds $MAX_LENGTH characters" }
        require('\\' !in value && '\u0000' !in value && value.split('/').all(String::isNotBlank)) {
            "Tag path contains an invalid separator"
        }
    }
}

enum class TagSelectionMode { Exact, Subtree }

data class TagSelection(
    val path: TagPath,
    val mode: TagSelectionMode,
)
