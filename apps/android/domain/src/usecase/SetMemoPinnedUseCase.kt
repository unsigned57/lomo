package com.lomo.domain.usecase

import com.lomo.domain.model.MemoOperationId
import com.lomo.domain.repository.MemoMutationRepository

class SetMemoPinnedUseCase(
    private val memoMutationRepository: MemoMutationRepository,
) {
    suspend operator fun invoke(
        memoId: String,
        pinned: Boolean,
        operationId: MemoOperationId,
    ) {
        memoMutationRepository.setMemoPinned(
            memoId = memoId,
            pinned = pinned,
            operationId = operationId,
        )
    }
}
