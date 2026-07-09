package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevisionCursor
import com.lomo.domain.model.MemoRevisionPage
import com.lomo.domain.repository.MemoVersionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class LoadMemoRevisionHistoryUseCase(
    private val repository: MemoVersionRepository,
) {
    fun historyEnabled(): Flow<Boolean> = flowOf(true)

    suspend operator fun invoke(
        memo: Memo,
        cursor: MemoRevisionCursor? = null,
    ): MemoRevisionPage =
        repository.listMemoRevisions(
            memo = memo,
            cursor = cursor,
            limit = DEFAULT_REVISION_LIMIT,
        )

    private companion object {
        const val DEFAULT_REVISION_LIMIT = 20
    }
}
