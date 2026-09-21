package com.lomo.data.reminder

sealed interface ReminderReceiverWorkResult {
    data object Completed : ReminderReceiverWorkResult

    data object Cancelled : ReminderReceiverWorkResult

    data class Failed(
        val cause: Throwable,
    ) : ReminderReceiverWorkResult
}
