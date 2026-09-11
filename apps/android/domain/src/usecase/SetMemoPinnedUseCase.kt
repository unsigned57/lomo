package com.lomo.domain.usecase

import com.lomo.domain.repository.MemoMutationRepository

class SetMemoPinnedUseCase(
    private val memoMutationRepository: MemoMutationRepository,
) {
    suspend operator fun invoke(
        memoId: String,
        pinned: Boolean,
    ) {
        memoMutationRepository.setMemoPinned(
            memoId = memoId,
            pinned = pinned,
        )
    }
}
