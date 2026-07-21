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
                "" -> NONE
                "d" -> DAILY
                "w" -> WEEKLY
                else -> throw IllegalArgumentException("Unsupported reminder recurrence code: $code")
            }
    }
}

data class ReminderReference(
    val opaqueId: String,
    val revision: String,
    val memoIdentity: String,
    val sourceSpan: com.lomo.domain.model.markdown.MarkdownSourceSpan,
    val tokenFingerprint: String,
)

data class ReminderMarker(
    val dueAt: LocalDateTime,
    val repeatCount: Int,
    val firedCount: Int,
    val done: Boolean,
    val intervalMinutes: Int = 10,
    val recurrence: Recurrence = Recurrence.NONE,
    val reference: ReminderReference,
    val token: String,
) {
    val isExhausted: Boolean
        get() = done || firedCount >= repeatCount

    fun copyWithFiredCount(newFiredCount: Int): ReminderMarker = copy(firedCount = newFiredCount)

    companion object {
        /** Display/format pattern for owner due_at_local wire fields only — not a token grammar. */
        val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH:mm")
    }
}
