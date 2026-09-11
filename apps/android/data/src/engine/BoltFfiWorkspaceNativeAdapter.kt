package com.lomo.data.engine

import com.lomo.domain.model.markdown.MarkdownRenderBlock
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownRenderInline
import com.lomo.domain.model.markdown.MarkdownRenderListItem
import com.lomo.domain.model.markdown.MarkdownRenderTableCell
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.nativebridge.RenderDocument
import com.lomo.nativebridge.RenderNode
import com.lomo.nativebridge.RenderNodeKind
import com.lomo.nativebridge.WorkspaceDocumentCommandKind
import com.lomo.nativebridge.WorkspaceDocumentHistoryWrite
import com.lomo.nativebridge.WorkspaceReminderReference

internal fun WorkspaceNativeCommandSpec.toBridge(): WorkspaceDocumentCommandKind =
    when (this) {
        is WorkspaceNativeCommandSpec.Create ->
            WorkspaceDocumentCommandKind.Create(
                timePart = timePart,
                content = content,
            )
        is WorkspaceNativeCommandSpec.Append ->
            WorkspaceDocumentCommandKind.Append(
                timePart = timePart,
                content = content,
            )
        is WorkspaceNativeCommandSpec.Replace ->
            WorkspaceDocumentCommandKind.Replace(
                identity = identity,
                content = content,
            )
        is WorkspaceNativeCommandSpec.Remove ->
            WorkspaceDocumentCommandKind.Remove(identity = identity)
        is WorkspaceNativeCommandSpec.ToggleTask ->
            WorkspaceDocumentCommandKind.ToggleTask(
                identity = identity,
                bodyStart = bodyStart,
                bodyEnd = bodyEnd,
            )
        is WorkspaceNativeCommandSpec.RewriteReminder ->
            WorkspaceDocumentCommandKind.RewriteReminder(
                reminder = reminder.toBridge(),
                replacement = replacement,
            )
    }

internal fun WorkspaceNativeHistoryWrite.toBridge(): WorkspaceDocumentHistoryWrite =
    WorkspaceDocumentHistoryWrite(revision = revision, createdAtMs = createdAtMs)

internal fun WorkspaceNativeCommandSpec.historyWrite(): WorkspaceNativeHistoryWrite? =
    when (this) {
        is WorkspaceNativeCommandSpec.Create -> history
        is WorkspaceNativeCommandSpec.Append -> history
        is WorkspaceNativeCommandSpec.Replace -> history
        is WorkspaceNativeCommandSpec.Remove,
        is WorkspaceNativeCommandSpec.ToggleTask,
        is WorkspaceNativeCommandSpec.RewriteReminder,
        -> null
    }

internal fun WorkspaceNativeExpectedState.toBridge(): com.lomo.nativebridge.WorkspaceDocumentExpectedState =
    when (this) {
        WorkspaceNativeExpectedState.Absent ->
            com.lomo.nativebridge.WorkspaceDocumentExpectedState.Absent
        is WorkspaceNativeExpectedState.Match ->
            com.lomo.nativebridge.WorkspaceDocumentExpectedState.Match(fingerprint)
    }

internal fun WorkspaceNativeTrashCommandSpec.toBridge(): com.lomo.nativebridge.WorkspaceTrashCommandKind =
    when (this) {
        is WorkspaceNativeTrashCommandSpec.Trash ->
            com.lomo.nativebridge.WorkspaceTrashCommandKind.Trash(
                identity = identity,
                chronologyEpochMs = chronologyEpochMs,
            )
        is WorkspaceNativeTrashCommandSpec.Restore ->
            com.lomo.nativebridge.WorkspaceTrashCommandKind.Restore(identity = identity)
        is WorkspaceNativeTrashCommandSpec.PermanentDelete ->
            com.lomo.nativebridge.WorkspaceTrashCommandKind.PermanentDelete(identity = identity)
    }

private fun WorkspaceReminderReferenceSnapshot.toBridge(): WorkspaceReminderReference =
    WorkspaceReminderReference(
        opaqueId = opaqueId,
        revision = revision,
        memoIdentity = memoIdentity,
        sourceStart = sourceStart,
        sourceEnd = sourceEnd,
        tokenFingerprint = tokenFingerprint,
        token = token,
        dueAtLocal = dueAtLocal,
        repeatCount = repeatCount,
        firedCount = firedCount,
        done = done,
        intervalMinutes = intervalMinutes,
        recurrenceCode = recurrenceCode,
    )

internal fun WorkspaceReminderReference.toSnapshot(): WorkspaceReminderReferenceSnapshot =
    WorkspaceReminderReferenceSnapshot(
        opaqueId = opaqueId,
        revision = revision,
        memoIdentity = memoIdentity,
        sourceStart = sourceStart,
        sourceEnd = sourceEnd,
        tokenFingerprint = tokenFingerprint,
        token = token,
        dueAtLocal = dueAtLocal,
        repeatCount = repeatCount,
        firedCount = firedCount,
        done = done,
        intervalMinutes = intervalMinutes,
        recurrenceCode = recurrenceCode,
    )
