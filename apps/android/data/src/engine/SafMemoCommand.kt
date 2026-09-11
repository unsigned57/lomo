package com.lomo.data.engine

/**
 * Executes the provider-side half of a permanent-delete batch and then publishes one projection
 * transaction.  SAF stores a day document containing several memo blocks, so targets sharing a
 * source path must consume the fingerprint produced by the previous removal; the projection
 * receives the final fingerprint for every sibling row.
 */
internal fun applySafPermanentDeleteManyOnSafAdapter(
    adapter: RustEngineAdapter,
    request: com.lomo.nativebridge.StoreMemoBatchDelete,
): com.lomo.nativebridge.StoreMemoBatchCommit {
    require(request.operationId.isNotBlank()) { "SAF permanent-delete batch operationId must be non-blank" }
    require(request.targets.isNotEmpty()) { "SAF permanent-delete batch must contain targets" }

    val currentFingerprintByPath = linkedMapOf<String, String>()
    val resultFingerprintByPath = linkedMapOf<String, String>()
    val verifiedTargets =
        request.targets.map { target ->
            require(target.memoId.isNotBlank()) { "SAF batch memoId must be non-blank" }
            require(target.sourcePath.isNotBlank()) { "SAF batch sourcePath must be non-blank" }
            require(target.expectedFingerprint.isNotBlank()) {
                "SAF batch expected fingerprint must be non-blank"
            }
            val expectedFingerprint =
                currentFingerprintByPath[target.sourcePath] ?: target.expectedFingerprint
            val result =
                adapter.executeTrashCommand(
                    path = target.sourcePath,
                    expectedFingerprint = expectedFingerprint,
                    command =
                        WorkspaceNativeTrashCommandSpec.PermanentDelete(
                            identity = target.memoId,
                        ),
                )
            result.requireAffectedMemo(
                path = target.sourcePath,
                identity = target.memoId,
                expectedSourceFingerprint = expectedFingerprint,
            )
            require(result.trashedAtMs == null) {
                "Permanent delete result must not retain a trash timestamp"
            }
            currentFingerprintByPath[target.sourcePath] = result.resultFingerprint
            resultFingerprintByPath[target.sourcePath] = result.resultFingerprint
            target
        }.map { target ->
            target.copy(resultFingerprint = checkNotNull(resultFingerprintByPath[target.sourcePath]))
        }

    return adapter.commitSafPermanentDeleteMany(
        request.copy(targets = verifiedTargets),
    )
}
