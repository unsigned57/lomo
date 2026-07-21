package com.lomo.domain.repository

import com.lomo.domain.model.Recurrence
import java.time.LocalDateTime

/**
 * Owner-issued canonical reminder token construction.
 *
 * Implementations must call the workspace engine grammar — Kotlin must not mint reminder token
 * strings from a second local format builder.
 */
interface ReminderTokenFactory {
    fun buildInsertToken(
        dueAt: LocalDateTime,
        repeatCount: Int,
        intervalMinutes: Int = 10,
        recurrence: Recurrence = Recurrence.NONE,
    ): String

    fun planMarkDone(currentToken: String): String

    fun planRecordFired(currentToken: String): String
}
