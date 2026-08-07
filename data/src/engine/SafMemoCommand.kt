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
    val before = adapter.scanAllMemoSnapshots(rootPath = null)
    val existing = if (command.kind == com.lomo.nativebridge.StoreMemoCommandKind.CREATE) {
        null
    } else {
        before.firstOrNull { it.identity == command.memoId }
    }
    return when (command.kind) {
        com.lomo.nativebridge.StoreMemoCommandKind.CREATE -> createSafMemo(adapter, command, before)
        com.lomo.nativebridge.StoreMemoCommandKind.UPDATE -> updateSafMemo(adapter, command, existing)
        com.lomo.nativebridge.StoreMemoCommandKind.DELETE -> deleteSafMemo(adapter, command, existing)
        com.lomo.nativebridge.StoreMemoCommandKind.PIN,
        com.lomo.nativebridge.StoreMemoCommandKind.UNPIN -> adapter.commitSafProjectionMutation(command, null)
        else -> error("SAF restore requires a dedicated platform mutation plan")
    }
}

private fun createSafMemo(
    adapter: RustEngineAdapter,
    command: com.lomo.nativebridge.StoreMemoCommand,
    before: Sequence<WorkspaceMemoSummarySnapshot>,
): com.lomo.nativebridge.StoreMemoCommit {
    val chronology = requireNotNull(command.chronologyEpochMs) { "SAF create requires chronologyEpochMs" }
    require(chronology > 0) { "SAF create chronologyEpochMs must be positive" }
    val local = Instant.ofEpochMilli(chronology).atZone(ZoneId.systemDefault())
    val dateKey = local.toLocalDate().format(
        StorageFilenameFormats.formatter(StorageFilenameFormats.DEFAULT_PATTERN),
    )
    val timePart = local.toLocalTime().format(
        StorageTimestampFormats.formatter(StorageTimestampFormats.DEFAULT_PATTERN),
    )
    val path = "$dateKey.md"
    val document = before.firstOrNull { it.path == path }
    val specification = if (document == null) {
        WorkspaceNativeCommandSpec.Create(timePart, requireNotNull(command.content))
    } else {
        WorkspaceNativeCommandSpec.Append(timePart, requireNotNull(command.content))
    }
    val jobId = adapter.startWorkspaceDocumentCommand(
        path,
        document?.let { WorkspaceNativeExpectedState.Match(it.fingerprint) }
            ?: WorkspaceNativeExpectedState.Absent,
        specification,
    )
    adapter.driveToCompletion(jobId)
    adapter.readWorkspaceDocumentCommandResult(jobId)
    val created = adapter.scanAllMemoSnapshots(null).firstOrNull {
        it.path == path && it.identity.startsWith("${dateKey}_${timePart}_")
    } ?: error("SAF create completed without one new scanned memo")
    return adapter.commitSafProjectionMutation(
        command.copy(memoId = created.identity),
        created.toSafProjectionSnapshot().toBridge(),
    )
}

private fun updateSafMemo(
    adapter: RustEngineAdapter,
    command: com.lomo.nativebridge.StoreMemoCommand,
    existing: WorkspaceMemoSummarySnapshot?,
): com.lomo.nativebridge.StoreMemoCommit {
    val snapshot = requireNotNull(existing) { "SAF update memo not found: ${command.memoId}" }
    val jobId = adapter.startWorkspaceDocumentCommand(
        snapshot.path,
        WorkspaceNativeExpectedState.Match(snapshot.fingerprint),
        WorkspaceNativeCommandSpec.Replace(snapshot.identity, requireNotNull(command.content)),
    )
    adapter.driveToCompletion(jobId)
    adapter.readWorkspaceDocumentCommandResult(jobId)
    val updated = adapter.findMemoSnapshot(command.memoId)
    return adapter.commitSafProjectionMutation(command, updated.toSafProjectionSnapshot().toBridge())
}

private fun deleteSafMemo(
    adapter: RustEngineAdapter,
    command: com.lomo.nativebridge.StoreMemoCommand,
    existing: WorkspaceMemoSummarySnapshot?,
): com.lomo.nativebridge.StoreMemoCommit {
    val snapshot = requireNotNull(existing) { "SAF delete memo not found: ${command.memoId}" }
    val jobId = adapter.startWorkspaceDocumentCommand(
        snapshot.path,
        WorkspaceNativeExpectedState.Match(snapshot.fingerprint),
        WorkspaceNativeCommandSpec.Remove(snapshot.identity),
    )
    adapter.driveToCompletion(jobId)
    adapter.readWorkspaceDocumentCommandResult(jobId)
    return adapter.commitSafProjectionMutation(command, null)
}
