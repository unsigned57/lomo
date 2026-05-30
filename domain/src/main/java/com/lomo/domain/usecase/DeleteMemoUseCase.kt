package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoMutationRepository

class DeleteMemoUseCase
(
        private val repository: MemoMutationRepository,
    ) {
        suspend operator fun invoke(memo: Memo) {
            repository.deleteMemo(memo)
        }
    }
