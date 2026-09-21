package com.lomo.data.reminder

import android.content.Intent

object ReminderRescheduleBroadcast {
    val ACTIONS: Set<String> =
        setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_DATE_CHANGED,
        )

    fun accepts(action: String?): Boolean = action != null && action in ACTIONS
}

internal fun enqueueReminderRescheduleDemand(
    action: String?,
    demand: ReminderRebuildDemand,
): Boolean {
    if (!ReminderRescheduleBroadcast.accepts(action)) {
        return false
    }
    demand.enqueue()
    return true
}
