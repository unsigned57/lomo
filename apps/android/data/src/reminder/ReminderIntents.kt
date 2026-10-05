package com.lomo.data.reminder

import com.lomo.domain.model.ReminderDeepLink

internal object ReminderIntents {
    const val ACTION_FIRE = "com.lomo.reminder.action.FIRE"

    /** The open deep link is a cross-module contract owned by [ReminderDeepLink]. */
    const val ACTION_OPEN = ReminderDeepLink.ACTION_OPEN
    const val ACTION_SNOOZE = "com.lomo.reminder.action.SNOOZE"
    const val ACTION_DONE = "com.lomo.reminder.action.DONE"
    const val EXTRA_MEMO_ID = ReminderDeepLink.EXTRA_MEMO_ID
    const val EXTRA_REMINDER_ID = "reminder_id"
    const val EXTRA_OCCURRENCE_ID = "occurrence_id"
    const val ALARM_DATA_URI_PREFIX = "lomo-reminder://alarm/"
    const val NOTIFICATION_CHANNEL_ID = "lomo.reminder"
}
