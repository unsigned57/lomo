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
): com.lomo.nativebridge.StoreMemoCommit {
    require(command.pendingPromotes.isEmpty()) {
        "SAF memo mutation with pending media requires the platform media transaction"
    }
    return when (command.kind) {
        com.lomo.nativebridge.StoreMemoCommandKind.CREATE -> createSafMemo(adapter, command)
        com.lomo.nativebridge.StoreMemoCommandKind.UPDATE,
        com.lomo.nativebridge.StoreMemoCommandKind.HISTORY_RESTORE ->
            replaceSafMemo(adapter, command, requireCurrentMemo(adapter, command, mustBeTrashed = false))
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
        com.lomo.nativebridge.StoreMemoCommandKind.UNPIN -> adapter.commitSafProjectionMutation(command, null)
    }
}

private fun createSafMemo(
    adapter: RustEngineAdapter,
    command: com.lomo.nativebridge.StoreMemoCommand,
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
    val affected = result.requireAffectedMemo(path = path)
    val projection =
        affected.toSafProjection(
            documentFingerprint = result.resultFingerprint,
            chronologyEpochMs = chronology,
            body = content,
        )
    return adapter.commitSafProjectionMutation(
        command.copy(memoId = affected.identity),
        projection.toBridge(),
    )
}

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
