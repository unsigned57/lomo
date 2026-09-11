package com.lomo.data.engine

import com.lomo.nativebridge.WorkspaceReminderReference

/**
 Scan and command-result snapshot projections over native workspace pages.
 */

internal fun com.lomo.nativebridge.WorkspaceScanPage.toSnapshot(
    exchangeResolver: ExchangeResolver,
): WorkspaceScanPageSnapshot =
    WorkspaceScanPageSnapshot(
        items =
            items.map { item ->
                WorkspaceMemoSummarySnapshot(
                    path = item.path,
                    identity = item.identity,
                    timePart = item.timePart,
                    fingerprint = item.fingerprint,
                    tags = item.tags,
                    attachments = item.attachments,
                    reminders =
                        item.reminders.map { reminder ->
                            WorkspaceReminderReferenceSnapshot(
                                opaqueId = reminder.opaqueId,
                                revision = reminder.revision,
                                memoIdentity = reminder.memoIdentity,
                                sourceStart = reminder.sourceStart,
                                sourceEnd = reminder.sourceEnd,
                                tokenFingerprint = reminder.tokenFingerprint,
                                token = reminder.token,
                                dueAtLocal = reminder.dueAtLocal,
                                repeatCount = reminder.repeatCount,
                                firedCount = reminder.firedCount,
                                done = reminder.done,
                                intervalMinutes = reminder.intervalMinutes,
                                recurrenceCode = reminder.recurrenceCode,
                            )
                        },
                    hasTodo = item.hasTodo,
                    hasUrl = item.hasUrl,
                    content =
                        exchangeResolver.consumeUtf8Artifact(
                            ExchangeArtifactReference(
                                token = item.content.exchangeToken,
                                length = item.content.length,
                                digest = item.content.digest,
                            ),
                        ),
                    bodyStart = item.bodyStart,
                    bodyEnd = item.bodyEnd,
                    startLine = item.startLine,
                    endLine = item.endLine,
                )
            },
        nextCursor = nextCursor,
    )

internal fun com.lomo.nativebridge.WorkspaceScanPage.toProjectionSnapshot(): WorkspaceProjectionScanPageSnapshot =
    WorkspaceProjectionScanPageSnapshot(
        items =
            items.map { item ->
                SafMemoProjectionReferenceSnapshot(
                    memoId = item.identity,
                    sourcePath = item.path,
                    fileFingerprint = item.fingerprint,
                    chronologyEpochMs = requireChronologyEpochMs(item.identity, item.timePart),
                    content =
                        ExchangeArtifactReference(
                            token = item.content.exchangeToken,
                            length = item.content.length,
                            digest = item.content.digest,
                        ),
                    tags = item.tags,
                    attachmentPaths = item.attachments,
                    hasTodo = item.hasTodo,
                    hasUrl = item.hasUrl,
                    reminders = item.reminders.map(WorkspaceReminderReference::toSnapshot),
                )
            },
        nextCursor = nextCursor,
    )

internal fun com.lomo.nativebridge.WorkspaceTrashScanPage.toProjectionSnapshot():
    WorkspaceTrashProjectionScanPageSnapshot =
    WorkspaceTrashProjectionScanPageSnapshot(
        items =
            items.map { item ->
                SafTrashProjectionReferenceSnapshot(
                    memoId = item.memoId,
                    sourcePath = item.sourcePath,
                    fileFingerprint = item.sourceFingerprint,
                    chronologyEpochMs = item.chronologyEpochMs,
                    trashedAtMs = item.trashedAtMs,
                    content =
                        ExchangeArtifactReference(
                            token = item.content.exchangeToken,
                            length = item.content.length,
                            digest = item.content.digest,
                        ),
                    tags = item.tags,
                    attachmentPaths = item.attachments,
                    hasTodo = item.hasTodo,
                    hasUrl = item.hasUrl,
                    reminders = item.reminders.map(WorkspaceReminderReference::toSnapshot),
                )
            },
        nextCursor = nextCursor,
    )

internal fun com.lomo.nativebridge.WorkspaceHistoryScanPage.toProjectionSnapshot():
    WorkspaceHistoryProjectionScanPageSnapshot =
    WorkspaceHistoryProjectionScanPageSnapshot(
        items =
            items.map { item ->
                SafHistoryProjectionReferenceSnapshot(
                    memoId = item.memoId,
                    revision = item.revision,
                    createdAtMs = item.createdAtMs,
                    fileFingerprint = item.fileFingerprint,
                    content =
                        ExchangeArtifactReference(
                            token = item.content.exchangeToken,
                            length = item.content.length,
                            digest = item.content.digest,
                        ),
                )
            },
        nextCursor = nextCursor,
    )

internal fun com.lomo.nativebridge.WorkspaceDocumentCommandResult.toSnapshot(): WorkspaceNativeCommandResultSnapshot =
    WorkspaceNativeCommandResultSnapshot(
        path = path,
        resultFingerprint = resultFingerprint,
        bytesWritten = bytesWritten,
        affectedMemo = affectedMemo?.toSnapshot(),
    )

internal fun com.lomo.nativebridge.WorkspaceTrashCommandResult.toSnapshot():
    WorkspaceNativeTrashCommandResultSnapshot =
    WorkspaceNativeTrashCommandResultSnapshot(
        path = path,
        resultFingerprint = resultFingerprint,
        affectedMemo = affectedMemo.toSnapshot(),
        trashedAtMs = trashedAtMs,
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
