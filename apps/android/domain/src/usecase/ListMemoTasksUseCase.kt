package com.lomo.domain.usecase

import com.lomo.domain.model.MemoTask
import com.lomo.domain.repository.MemoTaskRepository

class ListMemoTasksUseCase(
    private val memoTaskRepository: MemoTaskRepository,
) {
    suspend operator fun invoke(): List<MemoTask> = memoTaskRepository.listTasks()
}
