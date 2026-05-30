package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoMutationRepository

open class UpdateMemoContentUseCase
(
        private val repository: MemoMutationRepository,
        private val validator: ValidateMemoContentUseCase,
        private val resolveMemoUpdateActionUseCase: ResolveMemoUpdateActionUseCase,
        private val deleteMemoUseCase: DeleteMemoUseCase,
    ) {
        open suspend operator fun invoke(
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
