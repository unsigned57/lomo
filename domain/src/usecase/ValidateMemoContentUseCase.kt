package com.lomo.domain.usecase

import com.lomo.domain.model.MemoConstraints

sealed interface MemoValidationResult {
    data object Valid : MemoValidationResult

    sealed interface Invalid : MemoValidationResult {
        val message: String

        data object EmptyContentForCreate : Invalid {
            override val message: String = ValidateMemoContentUseCase.EMPTY_CONTENT_MESSAGE
        }

        data object EmptyContentForUpdate : Invalid {
            override val message: String = ValidateMemoContentUseCase.EMPTY_CONTENT_MESSAGE
        }

        data class ContentTooLong(
            val maxLength: Int,
            val actualLength: Int,
        ) : Invalid {
            override val message: String = ValidateMemoContentUseCase.lengthExceededMessage(maxLength)
        }
    }
}

class MemoValidationException(
    val reason: MemoValidationResult.Invalid,
) : IllegalArgumentException(reason.message)

/**
 * Centralized memo content validation rules.
 *
 * Keep these rules in domain so every write path shares the same boundary checks.
 */
class ValidateMemoContentUseCase {
        fun validateCreate(content: String): MemoValidationResult =
            when {
                content.isBlank() -> {
                    MemoValidationResult.Invalid.EmptyContentForCreate
                }

                content.length > MemoConstraints.MAX_MEMO_LENGTH -> {
                    MemoValidationResult.Invalid.ContentTooLong(
                        maxLength = MemoConstraints.MAX_MEMO_LENGTH,
                        actualLength = content.length,
                    )
                }

                else -> {
                    MemoValidationResult.Valid
                }
            }

        fun validateUpdate(content: String): MemoValidationResult =
            when {
                content.isBlank() -> {
                    MemoValidationResult.Invalid.EmptyContentForUpdate
                }

                content.length > MemoConstraints.MAX_MEMO_LENGTH -> {
                    MemoValidationResult.Invalid.ContentTooLong(
                        maxLength = MemoConstraints.MAX_MEMO_LENGTH,
                        actualLength = content.length,
                    )
                }

                else -> {
                    MemoValidationResult.Valid
                }
            }

        fun requireValidForCreate(content: String) {
            validateCreate(content).throwIfInvalid()
        }

        fun requireValidForUpdate(content: String) {
            validateUpdate(content).throwIfInvalid()
        }

        private fun MemoValidationResult.throwIfInvalid() {
            if (this is MemoValidationResult.Invalid) {
                throw MemoValidationException(this)
            }
        }

        companion object {
            const val EMPTY_CONTENT_MESSAGE = "Cannot save empty memo"

            fun lengthExceededMessage(maxLength: Int = MemoConstraints.MAX_MEMO_LENGTH): String =
                "Content exceeds limit of $maxLength characters"
        }
}
