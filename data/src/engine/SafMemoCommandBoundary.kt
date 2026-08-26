package com.lomo.data.engine

import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineRetryDisposition

internal fun requireCurrentMemo(
    adapter: RustEngineAdapter,
    command: com.lomo.nativebridge.StoreMemoCommand,
    mustBeTrashed: Boolean,
): com.lomo.nativebridge.StoreMemoSnapshot {
    val existing = adapter.getMemo(command.memoId)
    if (
        existing != null &&
        existing.summary.contentRevision == command.expectedRevision &&
        existing.summary.fileFingerprint == command.expectedFingerprint &&
        existing.summary.isTrashed == mustBeTrashed
    ) {
        return existing
    }
    val failure =
        when {
            existing == null ->
                engineCommandFailure(
                    category = EngineFailureCategory.VALIDATION,
                    code = "memo_identity_not_found",
                    retryDisposition = EngineRetryDisposition.NEVER,
                    diagnostic = "Memo identity was not found in the active projection",
                    operationId = command.operationId,
                )
            existing.summary.contentRevision != command.expectedRevision ||
                existing.summary.fileFingerprint != command.expectedFingerprint ->
                engineCommandFailure(
                    category = EngineFailureCategory.CONFLICT,
                    code = "stale_snapshot",
                    retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                    diagnostic = "Memo projection changed before the workspace mutation began",
                    operationId = command.operationId,
                )
            mustBeTrashed ->
                engineCommandFailure(
                    category = EngineFailureCategory.VALIDATION,
                    code = "memo_not_trashed",
                    retryDisposition = EngineRetryDisposition.NEVER,
                    diagnostic = "Memo must be in durable trash before this mutation",
                    operationId = command.operationId,
                )
            else ->
                engineCommandFailure(
                    category = EngineFailureCategory.VALIDATION,
                    code = "memo_already_trashed",
                    retryDisposition = EngineRetryDisposition.NEVER,
                    diagnostic = "Active memo mutation cannot target a trashed memo",
                    operationId = command.operationId,
                )
        }
    throw failure
}

internal fun RustEngineAdapter.executeDocumentCommand(
    path: String,
    expectedState: WorkspaceNativeExpectedState,
    command: WorkspaceNativeCommandSpec,
): WorkspaceNativeCommandResultSnapshot =
    withEngineFailureConversion {
        val jobId = startWorkspaceDocumentCommand(path, expectedState, command)
        driveToCompletion(jobId)
        readWorkspaceDocumentCommandResult(jobId)
    }

internal fun RustEngineAdapter.executeTrashCommand(
    path: String,
    expectedFingerprint: String,
    command: WorkspaceNativeTrashCommandSpec,
): WorkspaceNativeTrashCommandResultSnapshot =
    withEngineFailureConversion {
        val jobId = startWorkspaceTrashCommand(path, expectedFingerprint, command)
        driveToCompletion(jobId)
        readWorkspaceTrashCommandResult(jobId)
    }

internal fun WorkspaceNativeCommandResultSnapshot.requireAffectedMemo(
    path: String,
    identity: String? = null,
): WorkspaceDocumentMemoFactsSnapshot {
    require(this.path == path) { "Document result path does not match the planned mutation path" }
    val affected = requireNotNull(affectedMemo) {
        "Completed document mutation did not publish Rust-parsed affected memo facts"
    }
    require(affected.path == path) { "Affected memo path does not match the document result" }
    require(affected.fingerprint == resultFingerprint) {
        "Affected memo fingerprint does not match the verified document result"
    }
    identity?.let { expected ->
        require(affected.identity == expected) { "Affected memo identity does not match the mutation target" }
    }
    return affected
}

internal fun WorkspaceNativeTrashCommandResultSnapshot.requireAffectedMemo(
    path: String,
    identity: String,
    expectedSourceFingerprint: String,
): WorkspaceDocumentMemoFactsSnapshot {
    require(this.path == path) { "Trash result path does not match the planned mutation path" }
    require(affectedMemo.path == path) { "Trash affected memo path does not match the command result" }
    require(affectedMemo.fingerprint == expectedSourceFingerprint) {
        "Trash affected memo fingerprint does not match the verified command source"
    }
    require(affectedMemo.identity == identity) {
        "Trash affected memo identity does not match the mutation target"
    }
    return affectedMemo
}

internal fun WorkspaceDocumentMemoFactsSnapshot.toSafProjection(
    documentFingerprint: String,
    chronologyEpochMs: Long,
    body: String,
    trashedAtMs: Long? = null,
): SafMemoProjectionSnapshot {
    require(chronologyEpochMs > 0) { "SAF memo chronology must be positive" }
    require(documentFingerprint.isNotBlank()) { "SAF document fingerprint must not be blank" }
    return SafMemoProjectionSnapshot(
        memoId = identity,
        sourcePath = path,
        fileFingerprint = documentFingerprint,
        chronologyEpochMs = chronologyEpochMs,
        body = body,
        tags = tags,
        attachmentPaths = attachments,
        hasTodo = hasTodo,
        hasUrl = hasUrl,
        reminders = reminders,
        trashedAtMs = trashedAtMs,
    )
}
