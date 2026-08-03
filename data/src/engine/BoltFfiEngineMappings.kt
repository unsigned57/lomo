package com.lomo.data.engine

import com.lomo.data.engine.lan.LanNetworkFacts
import com.lomo.nativebridge.EngineState

internal fun EngineState.toSnapshot(): NativeEngineSnapshot =
    when (this) {
        EngineState.AwaitingWorkspaceSelection -> NativeEngineSnapshot.AwaitingWorkspaceSelection
        is EngineState.Opening -> NativeEngineSnapshot.Opening(jobId = jobId)
        is EngineState.Ready -> NativeEngineSnapshot.Ready(coreRevision, eventSequence)
        is EngineState.ReadOnlyRecovery ->
            NativeEngineSnapshot.ReadOnlyRecovery(
                EngineFailureSnapshot(
                    category = failure.category,
                    code = failure.code,
                    retryDisposition = failure.retryDisposition,
                    diagnostic = failure.diagnostic,
                ),
            )
        EngineState.ShuttingDown -> NativeEngineSnapshot.ShuttingDown
    }

internal fun LanNetworkFacts.toBridge(): com.lomo.nativebridge.LanNetworkSnapshotDto =
    com.lomo.nativebridge.LanNetworkSnapshotDto(
        revision = revision,
        localNetworkPermissionGranted = localNetworkPermissionGranted,
        candidates =
            candidates.map { candidate ->
                com.lomo.nativebridge.LanBindCandidateDto(
                    host = candidate.host,
                    port = candidate.port,
                )
            },
    )
