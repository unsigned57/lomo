package com.lomo.domain.repository

import com.lomo.domain.model.ReminderMarker

/** Typed reminder capability owned by the active Rust workspace engine session. */
interface MarkdownReminderRepository {
    fun remindersForMemo(memoIdentity: String): List<ReminderMarker>

    /** Plans a token edit; callers commit its baseline/content through the session memo update. */
    suspend fun planReminderRewrite(
        memoId: String,
        reminderId: String,
        expectedToken: String,
        replacement: String,
    ): com.lomo.domain.model.ReminderRewritePlan
}
