package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository

class DeleteMemoUseCase
    constructor(
        private val memoMaintenanceUseCase: MemoMaintenanceUseCase,
    ) {
        constructor(repository: MemoRepository) : this(
            MemoMaintenanceUseCase(repository),
        )

        suspend operator fun invoke(memo: Memo) {
            memoMaintenanceUseCase.deleteMemo(memo)
        }
    }
