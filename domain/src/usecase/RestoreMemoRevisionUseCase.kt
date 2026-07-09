package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.repository.MemoMutationRepository

class RestoreMemoRevisionUseCase(
    private val repository: MemoMutationRepository,
) {
    suspend operator fun invoke(
        memo: Memo,
        revision: MemoRevision,
    ) {
        repository.restoreMemoRevision(
            currentMemo = memo,
            revisionId = revision.revisionId,
        )
    }
}
