package com.lomo.ui.component.card

import com.lomo.domain.model.ReminderMarker
import java.time.format.DateTimeFormatter

object ReminderDisplayFormatter {
    fun format(
        reminder: ReminderMarker,
        formatter: DateTimeFormatter,
        doneLabel: String,
    ): String {
        val displayTime = reminder.dueAt.format(formatter)
        val displayRepeat = if (reminder.repeatCount > 1) {
            if (reminder.firedCount > 0 && !reminder.done) {
                " x${reminder.repeatCount} (${reminder.firedCount}/${reminder.repeatCount})"
            } else {
                " x${reminder.repeatCount}"
            }
        } else {
            ""
        }
        val displayStatus = if (reminder.isExhausted) {
            " ($doneLabel)"
        } else {
            ""
        }
        return "$displayTime$displayRepeat$displayStatus"
    }
}
