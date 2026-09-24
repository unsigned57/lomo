package com.lomo.data.engine

import com.lomo.nativebridge.WorkspaceReminderReference

/**
 * Command-result snapshot projections over native workspace command results.
 */

internal fun com.lomo.nativebridge.WorkspaceDocumentCommandResult.toSnapshot(): WorkspaceNativeCommandResultSnapshot =
    WorkspaceNativeCommandResultSnapshot(
        path = path,
        resultFingerprint = resultFingerprint,
        bytesWritten = bytesWritten,
        affectedMemo = affectedMemo?.toSnapshot(),
    )

private fun com.lomo.nativebridge.WorkspaceDocumentMemoFacts.toSnapshot():
    WorkspaceDocumentMemoFactsSnapshot =
    WorkspaceDocumentMemoFactsSnapshot(
        path = path,
        identity = identity,
        timePart = timePart,
        fingerprint = fingerprint,
        tags = tags,
        attachments = attachments,
        reminders = reminders.map(WorkspaceReminderReference::toSnapshot),
        hasTodo = hasTodo,
        hasUrl = hasUrl,
        content = content,
    )
