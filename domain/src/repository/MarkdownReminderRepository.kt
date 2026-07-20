package com.lomo.domain.repository

import com.lomo.domain.model.ReminderMarker
import com.lomo.domain.model.ReminderReference

/** Typed reminder capability owned by the active Rust workspace engine session. */
interface MarkdownReminderRepository {
    fun remindersForMemo(memoIdentity: String): List<ReminderMarker>

    /** Rewrites exactly one Rust-issued reminder occurrence and returns the updated memo body. */
    suspend fun rewriteReminder(
        reference: ReminderReference,
        replacement: String,
    ): String
}
