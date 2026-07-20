package com.lomo.domain.usecase

import com.lomo.domain.repository.ReminderCoordinator

class MarkReminderDoneUseCase(
    private val reminderCoordinator: ReminderCoordinator,
) {
    suspend operator fun invoke(
        memoId: String,
        reminderId: String,
    ) {
        reminderCoordinator.markDone(memoId, reminderId)
    }
}
