package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.repository.MemoVersionRepository

class RestoreMemoRevisionUseCase(
    private val repository: MemoVersionRepository,
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
