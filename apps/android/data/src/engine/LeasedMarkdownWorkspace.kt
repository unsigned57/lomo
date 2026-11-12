package com.lomo.data.engine

import com.lomo.domain.model.ReminderMarker
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.domain.repository.MarkdownReminderRepository
import com.lomo.domain.repository.MarkdownWorkspaceRepository
import com.lomo.domain.repository.WorkspaceMutationLease

/**
 * Task mutation admission and read-only Markdown/reminder planning over the active session.
 *
 * Task writes acquire the shared mutation lease here. Reminder plans perform no writes; the
 * reminder coordinator holds that lease across planning and the canonical session update.
 * Reads are safe against a retiring engine because the session serialises adapter routes.
 */
internal class LeasedMarkdownWorkspace(
    private val delegate: ManagedEngineSession,
    private val lease: WorkspaceMutationLease,
) : MarkdownWorkspaceRepository,
    MarkdownReminderRepository {
    override fun renderMarkdown(content: String): MarkdownRenderDocument = delegate.renderMarkdown(content)

    override fun remindersForMemo(memoIdentity: String): List<ReminderMarker> = delegate.remindersForMemo(memoIdentity)

    override suspend fun toggleTask(
        operationId: com.lomo.domain.model.MemoOperationId,
        memoIdentity: String,
        actionSpan: MarkdownSourceSpan,
    ): String = lease.withWrite { delegate.toggleTask(operationId, memoIdentity, actionSpan) }

    override suspend fun planReminderRewrite(
        memoId: String,
        reminderId: String,
        expectedToken: String,
        replacement: String,
    ): com.lomo.domain.model.ReminderRewritePlan =
        delegate.planReminderRewrite(memoId, reminderId, expectedToken, replacement)
}
