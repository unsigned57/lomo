package com.lomo.data.engine

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
