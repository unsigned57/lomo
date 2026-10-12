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
    /**
     * Grammar default repeat interval — the owner constant (`DEFAULT_REMINDER_INTERVAL_MINUTES`),
     * projected through the implementation so UI never repeats a literal.
     */
    val defaultIntervalMinutes: Int

    /**
     * Builds one owner-issued insert token. [intervalMinutes] and [recurrence] are explicit
     * caller choices — the grammar default lives in Rust (`reminderDefaultIntervalMinutes`),
     * never in a Kotlin signature literal.
     */
    fun buildInsertToken(
        dueAt: LocalDateTime,
        repeatCount: Int,
        intervalMinutes: Int,
        recurrence: Recurrence,
    ): String

    fun planMarkDone(currentToken: String): String

    fun planRecordFired(currentToken: String): String
}
