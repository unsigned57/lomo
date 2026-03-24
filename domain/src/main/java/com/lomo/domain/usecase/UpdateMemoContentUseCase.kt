package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository

class UpdateMemoContentUseCase
(
        private val repository: MemoRepository,
        private val validator: ValidateMemoContentUseCase,
        private val resolveMemoUpdateActionUseCase: ResolveMemoUpdateActionUseCase,
        private val deleteMemoUseCase: DeleteMemoUseCase,
    ) {
        suspend operator fun invoke(
            memo: Memo,
            newContent: String,
        ) {
            when (resolveMemoUpdateActionUseCase(newContent)) {
                MemoUpdateAction.MOVE_TO_TRASH -> deleteMemoUseCase(memo)
                MemoUpdateAction.UPDATE_CONTENT -> {
                    validator.requireValidForUpdate(newContent)
                    repository.updateMemo(memo, newContent)
                }
            }
        }
    }
