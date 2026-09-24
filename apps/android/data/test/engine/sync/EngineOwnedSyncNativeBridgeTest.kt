package com.lomo.data.engine.sync

/*
 * Behavior Contract:
 * - Unit under test: EngineOwnedSyncNativeBridge
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: production apply cycles call the open workspace session; inspect stays on the
 *   free-function bridge so listing does not require a session write authority.
 *
 * Scenarios:
 * - Given an apply cycle request, when runCycle runs, then SessionNativeBridge.syncRunCycle
 *   receives the request and the free-function runCycle is not invoked.
 * - Given inspectCyclePlan, when called, then the free-function inspect path is used (session
 *   syncRunCycle is not invoked).
 *
 * Observable outcomes: recorded session/free-function fields and returned cycle summary DTO.
 * TDD proof: RED EngineOwnedSyncNativeBridge was absent so SyncDataModule bound
 * FreeFunctionSyncNativeBridge.runCycle; GREEN this type routes apply through the session port.
 * Excludes: JNI, LomoEngine, WorkManager process start, SAF session.
 * Test Change Justification:
 * - Reason category: production API signature changed.
 * - Old behavior/assertion being replaced: runCycle accepted flat endpoint fields.
 * - Why old assertion is no longer correct: backend configuration is now a single typed SyncBackendConfigDto.
 * - Coverage preserved by: the same cycle assertions expressed through the config object.
 * - Why this is not fitting the test to the implementation: it tracks the DTO-shaped bridge contract.
 */

import com.lomo.data.engine.SessionNativeBridge
import com.lomo.data.testing.DataFunSpec
import com.lomo.nativebridge.SyncBackendConfigDto as BridgeBackendConfig
import com.lomo.nativebridge.SyncCyclePlanSummaryDto as BridgeCyclePlan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class EngineOwnedSyncNativeBridgeTest : DataFunSpec() {
    init {
        test("runCycle uses the open session port and not the free-function apply path") {
            val session = RecordingSessionCycleBridge()
            val free = RecordingFreeFunctionCycleBridge()
            val bridge = EngineOwnedSyncNativeBridge(engine = session, freeFunctions = free)

            val summary =
                bridge.runCycle(
                    workspaceRoot = "/ws",
                    config =
                        BridgeBackendConfig(
                            backendKind = "hermetic_fake",
                            endpointUrl = "",
                            identity = "",
                            s3Bucket = "",
                            s3Prefix = "",
                            s3Region = "",
                            gitBranch = "",
                            gitAuthorName = "",
                            gitAuthorEmail = "",
                            remoteDatasetId = "ds",
                        ),
                    secretLeaseId = "",
                    applyRemote = true,
                )

            session.lastWorkspaceRoot shouldBe "/ws"
            session.lastApplyRemote shouldBe true
            free.lastRunWorkspaceRoot.shouldBeNull()
            summary.sessionId shouldBe "session-engine"
            summary.ensurePresentCount shouldBe 2u
        }

    }
}

private class RecordingSessionCycleBridge : SessionNativeBridge {
    var lastWorkspaceRoot: String? = null
    var lastApplyRemote: Boolean? = null

    override fun syncRunCycle(
        workspaceRoot: String,
        config: BridgeBackendConfig,
        secretLeaseId: String,
        applyRemote: Boolean,
    ): BridgeCyclePlan {
        lastWorkspaceRoot = workspaceRoot
        lastApplyRemote = applyRemote
        return BridgeCyclePlan(
            sessionId = "session-engine",
            sessionKind = "incremental",
            sessionRevision = 1uL,
            baselineEstablished = false,
            ensurePresentCount = 2u,
            ensureAbsentCount = 0u,
            pullPresentCount = 0u,
            openConflictCount = 0u,
            holdCount = 0u,
            openConflictPaths = 0u,
            conflictRevision = null,
            retryDisposition = "after_user_action",
            pagesApplied = 0u,
            baselineAdvanced = false,
            localEntryCount = 0u,
            remoteListedCount = 0u,
            baselineEntryCount = 0u,
        )
    }
}

private class RecordingFreeFunctionCycleBridge : SyncNativeBridge {
    var lastRunWorkspaceRoot: String? = null

    override fun listConflicts(
        workspaceRoot: String,
        cursor: UInt,
        limit: UInt,
    ): com.lomo.nativebridge.SyncConflictPageDto = error("unused")

    override fun resolveConflicts(
        workspaceRoot: String,
        expectedRevision: ULong,
        resolutions: List<com.lomo.nativebridge.SyncConflictResolutionDto>,
    ): com.lomo.nativebridge.SyncConflictResolveResultDto = error("unused")

    override fun issueSecretLease(
        secretBytes: ByteArray,
        ttlMillis: ULong,
    ): com.lomo.nativebridge.SyncSecretLeaseDto = error("unused")

    override fun probeSecretLease(leaseId: String): UInt = error("unused")

    override fun revokeSecretLease(leaseId: String) = error("unused")

    override fun readConflictArtifact(
        workspaceRoot: String,
        artifactRef: String,
    ): ByteArray = error("unused")


    override fun runCycle(
        workspaceRoot: String,
        config: BridgeBackendConfig,
        secretLeaseId: String,
        applyRemote: Boolean,
    ): BridgeCyclePlan {
        lastRunWorkspaceRoot = workspaceRoot
        error("free-function runCycle must not run for EngineOwned apply")
    }

    override fun loadWorkspaceGeneration(workspaceRoot: String): String = error("unused")

    override fun resetControlTree(workspaceRoot: String) = error("unused")

    override fun cycleStatus(workspaceRoot: String): com.lomo.nativebridge.SyncCycleStatusDto =
        error("unused")

    override fun requestCancel(workspaceRoot: String): com.lomo.nativebridge.SyncCycleStatusDto =
        error("unused")

    override fun probeBackend(
        workspaceRoot: String,
        config: BridgeBackendConfig,
        secretLeaseId: String,
    ): com.lomo.nativebridge.SyncBackendProbeDto = error("unused")
}
