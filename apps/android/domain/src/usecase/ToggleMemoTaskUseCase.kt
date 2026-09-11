package com.lomo.domain.usecase

import com.lomo.domain.model.MemoTask
import com.lomo.domain.repository.MemoTaskRepository

class ToggleMemoTaskUseCase(
    private val memoTaskRepository: MemoTaskRepository,
) {
    suspend operator fun invoke(
        task: MemoTask,
        done: Boolean,
    ) {
        memoTaskRepository.toggleTask(task = task, done = done)
    }
}
