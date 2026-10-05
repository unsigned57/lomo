package com.lomo.data.reminder

import android.content.Intent

object ReminderRescheduleBroadcast {
    /**
     * LOCKED_BOOT_COMPLETED is intentionally absent: the receiver is not directBootAware and the
     * execution ledger lives in credential-encrypted storage, so a direct-boot broadcast could
     * never reach a readable ledger. BOOT_COMPLETED covers the real rebuild trigger.
     */
    val ACTIONS: Set<String> =
        setOf(
            Intent.ACTION_BOOT_COMPLETED,
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
