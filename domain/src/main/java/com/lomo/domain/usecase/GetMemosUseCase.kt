package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetMemosUseCase
    @Inject
    constructor(
        private val repository: MemoRepository,
    ) {
        operator fun invoke(): Flow<List<Memo>> = repository.getAllMemosList()
    }
