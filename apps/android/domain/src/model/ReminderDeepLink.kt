package com.lomo.domain.model

/**
 * Shared deep-link contract for the reminder "open memo" notification affordance.
 *
 * Producer: the data module's `ReminderNotifier` writes [ACTION_OPEN] with [EXTRA_MEMO_ID]
 * onto the content/activity intent. Consumer: the app module's launch-intent extraction.
 * The pair lives in domain so both modules compile against one constant set — a drift
 * between a producer literal and a consumer literal would silently dead-end reminder taps.
 */
object ReminderDeepLink {
    const val ACTION_OPEN = "com.lomo.reminder.action.OPEN"
    const val EXTRA_MEMO_ID = "memo_id"
}
