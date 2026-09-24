package com.lomo.data.engine

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
