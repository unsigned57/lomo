package com.lomo.domain.usecase

enum class MemoUpdateAction {
    UPDATE_CONTENT,
    MOVE_TO_TRASH,
}

/**
 * Single source for update-intent resolution.
 */
class ResolveMemoUpdateActionUseCase
    constructor() {
        operator fun invoke(newContent: String): MemoUpdateAction =
            if (newContent.isBlank()) {
                MemoUpdateAction.MOVE_TO_TRASH
            } else {
                MemoUpdateAction.UPDATE_CONTENT
            }
    }
