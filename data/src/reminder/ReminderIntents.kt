package com.lomo.data.reminder

internal object ReminderIntents {
    const val ACTION_FIRE = "com.lomo.reminder.action.FIRE"
    const val ACTION_OPEN = "com.lomo.reminder.action.OPEN"
    const val ACTION_SNOOZE = "com.lomo.reminder.action.SNOOZE"
    const val ACTION_DONE = "com.lomo.reminder.action.DONE"
    const val EXTRA_MEMO_ID = "memo_id"
    const val EXTRA_TOKEN_RAW = "token_raw"
    const val NOTIFICATION_CHANNEL_ID = "lomo.reminder"
}
