package com.lomo.domain.usecase

import androidx.paging.PagingData
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetMemosUseCase
    @Inject
    constructor(
        private val repository: MemoRepository,
    ) {
        operator fun invoke(): Flow<PagingData<Memo>> = repository.getAllMemos()
    }
