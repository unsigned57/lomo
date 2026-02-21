package com.lomo.domain.validation

import com.lomo.domain.AppConfig
import javax.inject.Inject

/**
 * Centralized memo content validation rules.
 *
 * Keep these rules in domain so every write path shares the same boundary checks.
 */
class MemoContentValidator
    @Inject
    constructor() {
        fun validateForCreate(content: String) {
            require(content.isNotBlank()) { EMPTY_CONTENT_MESSAGE }
            validateLength(content)
        }

        fun validateForUpdate(content: String) {
            // Blank update is allowed because repository treats it as delete.
            validateLength(content)
        }

        private fun validateLength(content: String) {
            require(content.length <= AppConfig.MAX_MEMO_LENGTH) { lengthExceededMessage() }
        }

        companion object {
            const val EMPTY_CONTENT_MESSAGE = "Cannot save empty memo"

            fun lengthExceededMessage(): String = "Content exceeds limit of ${AppConfig.MAX_MEMO_LENGTH} characters"
        }
    }
