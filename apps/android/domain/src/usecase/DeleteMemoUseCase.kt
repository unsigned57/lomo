package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoOperationId
import com.lomo.domain.repository.MemoMutationRepository

class DeleteMemoUseCase(
    private val repository: MemoMutationRepository,
) {
    /**
     * Deletes [memo] under the caller's frozen [operationId], so a retry replays one command
     * instead of issuing a second delete.
     */
    suspend operator fun invoke(
        memo: Memo,
        operationId: MemoOperationId,
    ) {
        repository.deleteMemo(memo, operationId)
    }
}
