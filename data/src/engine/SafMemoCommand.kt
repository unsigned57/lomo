package com.lomo.data.engine

import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.domain.model.StorageTimestampFormats
import java.time.Instant
import java.time.ZoneId

private const val MAX_WORKSPACE_SCAN_PAGE_SIZE: UInt = 63u

internal fun WorkspaceNativeAdapter.scanAllMemoSnapshots(
    rootPath: String?,
): Sequence<WorkspaceMemoSummarySnapshot> = sequence {
    var cursor: String? = null
    do {
        val jobId = startWorkspaceScan(MAX_WORKSPACE_SCAN_PAGE_SIZE, cursor, rootPath)
        driveToCompletion(jobId)
        val page = readWorkspaceScanPage(jobId)
        yieldAll(page.items)
        cursor = page.nextCursor
    } while (cursor != null)
}

internal fun applySafMemoCommandOnSafAdapter(
    adapter: RustEngineAdapter,
    command: com.lomo.nativebridge.StoreMemoCommand,
    onPublication: (com.lomo.nativebridge.StoreMemoCommit) -> Unit = {},
): com.lomo.nativebridge.StoreMemoCommit {
    if (command.pendingPromotes.isNotEmpty()) {
        require(
            command.kind == com.lomo.nativebridge.StoreMemoCommandKind.CREATE ||
                command.kind == com.lomo.nativebridge.StoreMemoCommandKind.UPDATE ||
                command.kind == com.lomo.nativebridge.StoreMemoCommandKind.HISTORY_RESTORE,
        ) {
            "SAF memo mutation kind ${command.kind} must not carry pendingPromotes"
        }
    }
    return when (command.kind) {
        com.lomo.nativebridge.StoreMemoCommandKind.CREATE ->
            createSafMemo(adapter, command, onPublication)
        com.lomo.nativebridge.StoreMemoCommandKind.UPDATE,
        com.lomo.nativebridge.StoreMemoCommandKind.HISTORY_RESTORE,
        -> {
            if (command.pendingPromotes.isNotEmpty()) {
                adapter.promoteSafMedia(command.pendingPromotes, command.operationId)
            }
            replaceSafMemo(adapter, command, requireCurrentMemo(adapter, command, mustBeTrashed = false))
        }
        com.lomo.nativebridge.StoreMemoCommandKind.DELETE ->
            deleteSafMemo(adapter, command, requireCurrentMemo(adapter, command, mustBeTrashed = false))
        com.lomo.nativebridge.StoreMemoCommandKind.RESTORE ->
            restoreSafMemo(adapter, command, requireCurrentMemo(adapter, command, mustBeTrashed = true))
        com.lomo.nativebridge.StoreMemoCommandKind.PERMANENT_DELETE ->
            permanentlyDeleteSafMemo(
                adapter,
                command,
                requireCurrentMemo(adapter, command, mustBeTrashed = true),
            )
        com.lomo.nativebridge.StoreMemoCommandKind.PIN,
        com.lomo.nativebridge.StoreMemoCommandKind.UNPIN,
        -> adapter.commitSafProjectionMutation(command, null)
    }
}

/**
 * Creates one SAF memo as begin → promote → document write → projection commit.
 *
 * Begin publishes the pending projection and allocates the identity before any durable platform
 * I/O, so the memo is list-visible while the slow SAF work is still running. The document command
 * and the projection commit must then both agree on that one identity; any failure — including the
 * identity disagreeing with the parsed document — rolls the pending row back so the list never
 * keeps a memo that never became durable.
 */
private fun createSafMemo(
    adapter: RustEngineAdapter,
    command: com.lomo.nativebridge.StoreMemoCommand,
    onPublication: (com.lomo.nativebridge.StoreMemoCommit) -> Unit,
): com.lomo.nativebridge.StoreMemoCommit {
    val chronology = requireNotNull(command.chronologyEpochMs) { "SAF create requires chronologyEpochMs" }
    require(chronology > 0) { "SAF create chronologyEpochMs must be positive" }
    require(command.expectedRevision == 0uL) { "SAF create expectedRevision must be zero" }
    val content = requireNotNull(command.content) { "SAF create requires content" }
    val local = Instant.ofEpochMilli(chronology).atZone(ZoneId.systemDefault())
    val dateKey = local.toLocalDate().format(
        StorageFilenameFormats.formatter(StorageFilenameFormats.DEFAULT_PATTERN),
    )
    val timePart = local.toLocalTime().format(
        StorageTimestampFormats.formatter(StorageTimestampFormats.DEFAULT_PATTERN),
    )
    val path = "$dateKey.md"

    val begin = adapter.beginSafMemoCreate(
        com.lomo.nativebridge.StoreSafMemoCreateBegin(
            operationId = command.operationId,
            dateKey = dateKey,
            timePart = timePart,
            chronologyEpochMs = chronology,
            sourcePath = path,
            body = content,
        ),
    )
    onPublication(begin.toCreatePublication(command.operationId))
    return try {
        if (command.pendingPromotes.isNotEmpty()) {
            adapter.promoteSafMedia(command.pendingPromotes, command.operationId)
        }
        val sourceFingerprint = adapter.sourceDocumentFingerprint(path)
        val specification =
            sourceFingerprint?.let {
                WorkspaceNativeCommandSpec.Append(
                    timePart = timePart,
                    content = content,
                    history = WorkspaceNativeHistoryWrite(revision = 1uL, createdAtMs = chronology),
                )
            } ?: WorkspaceNativeCommandSpec.Create(
                timePart = timePart,
                content = content,
                history = WorkspaceNativeHistoryWrite(revision = 1uL, createdAtMs = chronology),
            )
        val expectedState =
            sourceFingerprint?.let(WorkspaceNativeExpectedState::Match)
                ?: WorkspaceNativeExpectedState.Absent
        val result = adapter.executeDocumentCommand(path, expectedState, specification)
        val affected = result.requireAffectedMemo(path = path, identity = begin.memoId)
        val projection =
            affected.toSafProjection(
                documentFingerprint = result.resultFingerprint,
                chronologyEpochMs = chronology,
                body = content,
            )
        adapter.commitSafProjectionMutation(
            command.copy(memoId = begin.memoId),
            projection.toBridge(),
        )
    } catch (failure: Exception) {
        rollbackPendingSafCreate(adapter, command, begin.memoId, onPublication, failure)
        throw failure
    }
}

/** Rolls the pending row back after a failed pipeline; a rollback failure cannot mask the cause. */
private fun rollbackPendingSafCreate(
    adapter: RustEngineAdapter,
    command: com.lomo.nativebridge.StoreMemoCommand,
    memoId: String,
    onPublication: (com.lomo.nativebridge.StoreMemoCommit) -> Unit,
    failure: Exception,
) {
    try {
        val rollback = adapter.rollbackSafMemoCreate(command.operationId, memoId)
        if (rollback.removed) {
            onPublication(rollback.toRollbackPublication(command.operationId, memoId))
        }
    } catch (rollbackFailure: Exception) {
        if (rollbackFailure is kotlinx.coroutines.CancellationException) throw rollbackFailure
        failure.addSuppressed(rollbackFailure)
    }
}

private fun com.lomo.nativebridge.StoreSafMemoCreateBeginResult.toCreatePublication(
    operationId: String,
): com.lomo.nativebridge.StoreMemoCommit =
    com.lomo.nativebridge.StoreMemoCommit(
        operationId = operationId,
        memoId = memoId,
        coreRevision = coreRevision,
        eventSequence = eventSequence,
        contentRevision = 1uL,
        fileFingerprint = "",
        scopes = scopes,
        idempotentReplay = idempotentReplay,
    )

private fun com.lomo.nativebridge.StoreSafMemoRollbackResult.toRollbackPublication(
    operationId: String,
    memoId: String,
): com.lomo.nativebridge.StoreMemoCommit =
    com.lomo.nativebridge.StoreMemoCommit(
        operationId = operationId,
        memoId = memoId,
        coreRevision = coreRevision,
        eventSequence = eventSequence,
        contentRevision = 0uL,
        fileFingerprint = "",
        scopes = scopes,
        idempotentReplay = false,
    )

private fun replaceSafMemo(
    adapter: RustEngineAdapter,
    command: com.lomo.nativebridge.StoreMemoCommand,
    existing: com.lomo.nativebridge.StoreMemoSnapshot,
): com.lomo.nativebridge.StoreMemoCommit {
    val content = requireNotNull(command.content) { "SAF document replacement requires content" }
    val committedAtMs = requireNotNull(command.chronologyEpochMs) {
        "SAF document replacement requires chronologyEpochMs"
    }
    require(committedAtMs > 0) { "SAF document replacement chronologyEpochMs must be positive" }
    val summary = existing.summary
    val result =
        adapter.executeDocumentCommand(
            path = summary.sourcePath,
            expectedState = WorkspaceNativeExpectedState.Match(summary.fileFingerprint),
            command =
                WorkspaceNativeCommandSpec.Replace(
                    identity = summary.memoId,
                    content = content,
                    history =
                        WorkspaceNativeHistoryWrite(
                            revision = (summary.contentRevision + 1uL),
                            createdAtMs = committedAtMs,
                        ),
                ),
        )
    val affected = result.requireAffectedMemo(path = summary.sourcePath, identity = summary.memoId)
    return adapter.commitSafProjectionMutation(
        command,
        affected
            .toSafProjection(
                documentFingerprint = result.resultFingerprint,
                chronologyEpochMs = summary.createdAtMs,
                body = content,
            ).toBridge(),
    )
}

private fun deleteSafMemo(
    adapter: RustEngineAdapter,
    command: com.lomo.nativebridge.StoreMemoCommand,
    existing: com.lomo.nativebridge.StoreMemoSnapshot,
): com.lomo.nativebridge.StoreMemoCommit {
    val summary = existing.summary
    val result =
        adapter.executeTrashCommand(
            path = summary.sourcePath,
            expectedFingerprint = summary.fileFingerprint,
            command =
                WorkspaceNativeTrashCommandSpec.Trash(
                    identity = summary.memoId,
                    chronologyEpochMs = summary.createdAtMs,
                ),
        )
    val affected =
        result.requireAffectedMemo(
            path = summary.sourcePath,
            identity = summary.memoId,
            expectedSourceFingerprint = summary.fileFingerprint,
        )
    require(result.resultFingerprint == summary.fileFingerprint) {
        "Soft delete must not rewrite the active source document"
    }
    val trashedAtMs = requireNotNull(result.trashedAtMs) {
        "Completed soft delete did not publish its durable trash timestamp"
    }
    return adapter.commitSafProjectionMutation(
        command,
        affected.toSafProjection(
            documentFingerprint = result.resultFingerprint,
            chronologyEpochMs = summary.createdAtMs,
            body = existing.body,
            trashedAtMs = trashedAtMs,
        ).toBridge(),
    )
}

private fun restoreSafMemo(
    adapter: RustEngineAdapter,
    command: com.lomo.nativebridge.StoreMemoCommand,
    existing: com.lomo.nativebridge.StoreMemoSnapshot,
): com.lomo.nativebridge.StoreMemoCommit {
    val summary = existing.summary
    val result =
        adapter.executeTrashCommand(
            path = summary.sourcePath,
            expectedFingerprint = summary.fileFingerprint,
            command = WorkspaceNativeTrashCommandSpec.Restore(identity = summary.memoId),
        )
    val affected =
        result.requireAffectedMemo(
            path = summary.sourcePath,
            identity = summary.memoId,
            expectedSourceFingerprint = summary.fileFingerprint,
        )
    require(result.resultFingerprint == summary.fileFingerprint) {
        "Restore must not rewrite the active source document"
    }
    require(result.trashedAtMs == null) { "Restore result must not retain a trash timestamp" }
    return adapter.commitSafProjectionMutation(
        command,
        affected
            .toSafProjection(
                documentFingerprint = result.resultFingerprint,
                chronologyEpochMs = summary.createdAtMs,
                body = existing.body,
            ).toBridge(),
    )
}

private fun permanentlyDeleteSafMemo(
    adapter: RustEngineAdapter,
    command: com.lomo.nativebridge.StoreMemoCommand,
    existing: com.lomo.nativebridge.StoreMemoSnapshot,
): com.lomo.nativebridge.StoreMemoCommit {
    val summary = existing.summary
    val result =
        adapter.executeTrashCommand(
            path = summary.sourcePath,
            expectedFingerprint = summary.fileFingerprint,
            command = WorkspaceNativeTrashCommandSpec.PermanentDelete(identity = summary.memoId),
        )
    val affected =
        result.requireAffectedMemo(
            path = summary.sourcePath,
            identity = summary.memoId,
            expectedSourceFingerprint = summary.fileFingerprint,
        )
    require(result.trashedAtMs == null) { "Permanent delete result must not retain a trash timestamp" }
    return adapter.commitSafProjectionMutation(
        command,
        affected
            .toSafProjection(
                documentFingerprint = result.resultFingerprint,
                chronologyEpochMs = summary.createdAtMs,
                body = existing.body,
            ).toBridge(),
    )
}
