package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.usecase.ValidateMemoContentUseCase

class UpdateMemoContentUseCase
    constructor(
        private val repository: MemoRepository,
        private val validator: ValidateMemoContentUseCase,
    ) {
        suspend operator fun invoke(
            memo: Memo,
            newContent: String,
        ) {
            validator.requireValidForUpdate(newContent)
            repository.updateMemo(memo, newContent)
        }
    }
