package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetFilteredMemosUseCase
    @Inject
    constructor(
        private val repository: MemoRepository,
    ) {
        operator fun invoke(
            query: String,
            tag: String?,
        ): Flow<List<Memo>> =
            when {
                tag != null -> repository.getMemosByTagList(tag)
                query.isNotBlank() -> repository.searchMemosList(query)
                else -> repository.getAllMemosList()
            }
    }
