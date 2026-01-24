package com.lomo.domain.usecase

import com.lomo.domain.model.MemoStatistics
import com.lomo.domain.repository.MemoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

class GetMemoStatsUseCase
    @Inject
    constructor(
        private val repository: MemoRepository,
    ) {
        operator fun invoke(): Flow<MemoStatistics> =
            combine(
                repository.getMemoCount(),
                repository.getTagCounts(),
                repository.getAllTimestamps(),
            ) { count, tags, timestamps ->
                MemoStatistics(totalMemos = count, tagCounts = tags, timestamps = timestamps)
            }
    }
