package com.lomo.data.engine

import com.lomo.domain.model.ReminderMarker
import com.lomo.domain.model.ReminderReference
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.domain.repository.MarkdownReminderRepository
import com.lomo.domain.repository.MarkdownWorkspaceRepository
import com.lomo.domain.repository.WorkspaceMutationLease

/**
 * Owning boundary that admits every Markdown/reminder mutation through the workspace lease.
 *
 * The condition lives here once instead of being copied into each use case: a background alarm
 * marking a reminder done and a foreground checkbox toggle reach the same engine, so both must be
 * admitted and drained by the same barrier. Reads stay unleased — they are safe against a
 * retiring engine because the session serialises routes behind its own adapter lease.
 */
internal class LeasedMarkdownWorkspace(
    private val delegate: ManagedEngineSession,
    private val lease: WorkspaceMutationLease,
) : MarkdownWorkspaceRepository,
    MarkdownReminderRepository {
    override fun renderMarkdown(content: String): MarkdownRenderDocument = delegate.renderMarkdown(content)

    override fun remindersForMemo(memoIdentity: String): List<ReminderMarker> =
        delegate.remindersForMemo(memoIdentity)

    override suspend fun toggleTask(
        memoIdentity: String,
        actionSpan: MarkdownSourceSpan,
    ): String = lease.withWrite { delegate.toggleTask(memoIdentity, actionSpan) }

    override suspend fun rewriteReminder(
        reference: ReminderReference,
        replacement: String,
    ): String = lease.withWrite { delegate.rewriteReminder(reference, replacement) }
}
