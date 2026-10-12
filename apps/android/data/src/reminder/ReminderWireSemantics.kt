package com.lomo.data.reminder

import com.lomo.domain.model.Recurrence
import com.lomo.domain.model.ReminderInterval
import com.lomo.nativebridge.ReminderSnoozeInterval
import java.time.format.DateTimeFormatter

/**
 * Boundary codec for the owner `due_at_local` wire field (`yyyy-MM-dd-HH:mm`). This is the single
 * Kotlin format used to decode wire facts for display and to encode caller-supplied local times
 * for owner-issued insert tokens; the grammar itself stays in `lomo-workspace`.
 */
internal val REMINDER_DUE_AT_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd-HH:mm")

/** Maps the owner recurrence wire enum to the domain vocabulary — exhaustive by construction. */
internal fun com.lomo.nativebridge.ReminderRecurrence.toDomainRecurrence(): Recurrence =
    when (this) {
        com.lomo.nativebridge.ReminderRecurrence.NONE -> Recurrence.NONE
        com.lomo.nativebridge.ReminderRecurrence.DAILY -> Recurrence.DAILY
        com.lomo.nativebridge.ReminderRecurrence.WEEKLY -> Recurrence.WEEKLY
    }

/** Maps a domain recurrence choice to the owner wire enum for token construction/mutation. */
internal fun Recurrence.toNativeRecurrence(): com.lomo.nativebridge.ReminderRecurrence =
    when (this) {
        Recurrence.NONE -> com.lomo.nativebridge.ReminderRecurrence.NONE
        Recurrence.DAILY -> com.lomo.nativebridge.ReminderRecurrence.DAILY
        Recurrence.WEEKLY -> com.lomo.nativebridge.ReminderRecurrence.WEEKLY
    }

/** Maps a domain snooze-interval preference to the owner wire option. */
internal fun ReminderInterval.toNativeSnoozeInterval(): ReminderSnoozeInterval =
    when (this) {
        ReminderInterval.ONE_MINUTE -> ReminderSnoozeInterval.ONE_MINUTE
        ReminderInterval.THREE_MINUTES -> ReminderSnoozeInterval.THREE_MINUTES
        ReminderInterval.FIVE_MINUTES -> ReminderSnoozeInterval.FIVE_MINUTES
        ReminderInterval.FIFTEEN_MINUTES -> ReminderSnoozeInterval.FIFTEEN_MINUTES
        ReminderInterval.THIRTY_MINUTES -> ReminderSnoozeInterval.THIRTY_MINUTES
        ReminderInterval.SIXTY_MINUTES -> ReminderSnoozeInterval.SIXTY_MINUTES
    }
