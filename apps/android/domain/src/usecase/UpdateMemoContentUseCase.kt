package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoMutationRepository

open class UpdateMemoContentUseCase
(
        private val repository: MemoMutationRepository,
        private val validator: ValidateMemoContentUseCase,
    ) {
        open suspend operator fun invoke(
            memo: Memo,
            newContent: String,
        ) {
            // Lifecycle transitions are explicit commands. An update can only update; blank
            // content is rejected by the same boundary used for every other invalid body.
            validator.requireValidForUpdate(newContent)
            repository.updateMemo(memo, newContent)
        }
    }
