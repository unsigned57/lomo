package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import javax.inject.Inject

class DeleteMemoUseCase
    @Inject
    constructor(
        private val repository: MemoRepository,
    ) {
        suspend operator fun invoke(memo: Memo) {
            repository.deleteMemo(memo)
        }
    }
