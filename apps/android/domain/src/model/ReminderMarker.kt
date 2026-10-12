package com.lomo.domain.model

import java.time.LocalDateTime

/**
 * Recurrence vocabulary projected from the owner token grammar.
 *
 * The Rust `ReminderRecurrence` wire enum carries this same closed set; Kotlin never encodes or
 * decodes grammar codes (`r` field) itself — the data layer maps variants verbatim.
 */
enum class Recurrence {
    NONE,
    DAILY,
    WEEKLY,
}

data class ReminderReference(
    val opaqueId: String,
    val revision: String,
    val memoIdentity: String,
    val sourceSpan: com.lomo.domain.model.markdown.MarkdownSourceSpan,
    val tokenFingerprint: String,
    val fingerprintOrdinal: UInt = 0u,
    /** Durable embedded reminder id (`#<hex>` token tail); null for legacy or ambiguous tokens. */
    val embeddedId: String? = null,
)

data class ReminderMarker(
    /** Verbatim owner `due_at_local` wire fact (`yyyy-MM-dd-HH:mm`); Kotlin never re-encodes it. */
    val dueAtLocal: String,
    /** Display projection of [dueAtLocal] decoded at the data boundary. */
    val dueAt: LocalDateTime,
    val repeatCount: Int,
    val firedCount: Int,
    val done: Boolean,
    val intervalMinutes: Int,
    val recurrence: Recurrence,
    val reference: ReminderReference,
    val token: String,
) {
    val isExhausted: Boolean
        get() = done || firedCount >= repeatCount

    fun copyWithFiredCount(newFiredCount: Int): ReminderMarker = copy(firedCount = newFiredCount)
}
