package com.lomo.domain.model

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class Recurrence(val code: String) {
    NONE(""),
    DAILY("d"),
    WEEKLY("w");

    companion object {
        fun fromCode(code: String): Recurrence =
            when (code) {
                "d" -> DAILY
                "w" -> WEEKLY
                else -> NONE
            }
    }
}

data class ReminderMarker(
    val dueAt: LocalDateTime,
    val repeatCount: Int,
    val firedCount: Int,
    val done: Boolean,
    val intervalMinutes: Int = 10,
    val recurrence: Recurrence = Recurrence.NONE,
    val tokenRange: IntRange,
    val raw: String,
) {
    val isExhausted: Boolean
        get() = done || firedCount >= repeatCount

    fun copyWithFiredCount(newFiredCount: Int): ReminderMarker = copy(firedCount = newFiredCount)

    fun canonicalToken(): String =
        canonicalToken(
            dueAt = dueAt,
            repeatCount = repeatCount,
            firedCount = firedCount,
            done = done,
            intervalMinutes = intervalMinutes,
            recurrence = recurrence,
        )

    companion object {
        val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH:mm")

        fun canonicalToken(
            dueAt: LocalDateTime,
            repeatCount: Int,
            firedCount: Int,
            done: Boolean,
            intervalMinutes: Int = 10,
            recurrence: Recurrence = Recurrence.NONE,
        ): String {
            val base = "@" + dueAt.format(TIMESTAMP_FORMAT)
            val repeatSuffix = if (repeatCount > 1) "x$repeatCount" else ""
            val intervalSuffix = if (repeatCount > 1 && intervalMinutes != 10) "i$intervalMinutes" else ""
            val recurrenceSuffix = if (recurrence != Recurrence.NONE) "r${recurrence.code}" else ""
            val stateSuffix =
                when {
                    done -> ".done"
                    repeatCount > 1 && firedCount > 0 -> ".$firedCount"
                    else -> ""
                }
            return base + repeatSuffix + intervalSuffix + recurrenceSuffix + stateSuffix
        }
    }
}
