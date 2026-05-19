package com.lomo.domain.model

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class ReminderMarker(
    val dueAt: LocalDateTime,
    val repeatCount: Int,
    val firedCount: Int,
    val done: Boolean,
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
        )

    companion object {
        val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH:mm")

        fun canonicalToken(
            dueAt: LocalDateTime,
            repeatCount: Int,
            firedCount: Int,
            done: Boolean,
        ): String {
            val base = "@" + dueAt.format(TIMESTAMP_FORMAT)
            val repeatSuffix = if (repeatCount > 1) "x$repeatCount" else ""
            val stateSuffix =
                when {
                    done -> ".done"
                    repeatCount > 1 && firedCount > 0 -> ".$firedCount"
                    else -> ""
                }
            return base + repeatSuffix + stateSuffix
        }
    }
}
