package com.lomo.data.engine.sync

/*
 * Behavior Contract:
 * - Unit under test: RustSyncCycleStatusStore
 * - Owning layer: data
 * - Priority tier: P1
 * - Capability: read and observe durable sync cycle records while fencing concurrent workspace
 *   transitions and staying idle on unmounted/SAF mounts.
 *
 * Scenarios:
 * - Given a concurrent Direct -> SAF switch during cycleStatus (workspaceRoot flips to null with
 *   sync_local_pull_requires_direct_workspace), when refresh() runs, then null is returned.
 * - Given a concurrent Direct A -> Direct B switch (workspaceRoot flips to /ws/direct-b with
 *   sync_cycle_workspace_mismatch), when refresh() runs, then Direct B status is returned.
 * - Given a concurrent unmount throwing IllegalStateException after workspaceRoot flips to null,
 *   when refresh() runs, then null is returned.
 * - Given switch-shaped or I/O failures when workspaceRoot did not change, when refresh() runs,
 *   then the original failure is re-thrown without swallowing.
 * - Given workspaceMounts flow, when mount is unmounted/SAF, activates Direct, and switches back
 *   to SAF, then observe() stays idle without polling SAF and emits null immediately on switch.
 *
 * Observable outcomes: refresh() return values/exceptions and observe() Flow emissions/call counts.
 * TDD proof:
 * - Target: ./kotlin test --include-module=data
 *   --include-classes='com.lomo.data.engine.sync.RustSyncCycleStatusStoreTest'
 * - RED: refresh()/observe() crashed with sync_local_pull_requires_direct_workspace when switching
 *   from Direct to SAF between reading WorkspaceFilesystemRoot and calling cycleStatus.
 * - GREEN: mount-reactive observe() skips non-Direct mounts and refresh() retries once when root changed.
 *
 * Excludes: Rust JNI FFI execution and UI ViewModels.
 */

import com.lomo.data.engine.media.WorkspaceFilesystemRoot
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.ProjectionFreshness
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.WorkspaceAuthority
import com.lomo.domain.model.WorkspaceMount
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class RustSyncCycleStatusStoreTest : DataFunSpec() {
    init {
        test("refresh returns null after concurrent switch from Direct to SAF") {
            var currentRoot: String? = "/ws/direct"
            val remote =
                RecordingStatusRemoteSyncRepository { root ->
                    root shouldBe "/ws/direct"
                    currentRoot = null
                    throw boundaryFailure("sync_local_pull_requires_direct_workspace")
                }
            val store =
                RustSyncCycleStatusStore(
                    remoteSync = remote,
                    workspaceRoot = WorkspaceFilesystemRoot { currentRoot },
                )

            store.refresh() shouldBe null
            remote.requestedRoots shouldBe listOf("/ws/direct")
        }

        test("refresh re-reads new Direct workspace after concurrent Direct-to-Direct switch") {
            var currentRoot: String? = "/ws/direct-a"
            val expectedB = sampleCycleStatus(cycleId = "cycle-b", updatedAtMs = 42L)
            val remote =
                RecordingStatusRemoteSyncRepository { root ->
                    when (root) {
                        "/ws/direct-a" -> {
                            currentRoot = "/ws/direct-b"
                            throw boundaryFailure("sync_cycle_workspace_mismatch")
                        }

                        "/ws/direct-b" -> expectedB

                        else -> error("unexpected root: $root")
                    }
                }
            val store =
                RustSyncCycleStatusStore(
                    remoteSync = remote,
                    workspaceRoot = WorkspaceFilesystemRoot { currentRoot },
                )

            store.refresh() shouldBe expectedB
            remote.requestedRoots shouldBe listOf("/ws/direct-a", "/ws/direct-b")
        }

        test("refresh returns null after concurrent unmount throws IllegalStateException") {
            var currentRoot: String? = "/ws/direct"
            val remote =
                RecordingStatusRemoteSyncRepository {
                    currentRoot = null
                    throw IllegalStateException("Workspace projection does not permit this operation")
                }
            val store =
                RustSyncCycleStatusStore(
                    remoteSync = remote,
                    workspaceRoot = WorkspaceFilesystemRoot { currentRoot },
                )

            store.refresh() shouldBe null
            remote.requestedRoots shouldBe listOf("/ws/direct")
        }

        test("refresh re-throws when failure occurs without an actual workspace root change") {
            val remoteMismatch =
                RecordingStatusRemoteSyncRepository {
                    throw boundaryFailure("sync_local_pull_requires_direct_workspace")
                }
            val storeMismatch =
                RustSyncCycleStatusStore(
                    remoteSync = remoteMismatch,
                    workspaceRoot = WorkspaceFilesystemRoot { "/ws/direct" },
                )

            shouldThrow<RemoteSyncBoundaryFailure> { storeMismatch.refresh() }
                .code shouldBe "sync_local_pull_requires_direct_workspace"

            var root: String? = "/ws/direct-1"
            val storeIo =
                RustSyncCycleStatusStore(
                    remoteSync =
                    RecordingStatusRemoteSyncRepository {
                        root = "/ws/direct-2"
                        throw RemoteSyncBoundaryFailure(
                            category = "storage",
                            code = "io_error",
                            retryDisposition = "transient",
                            diagnostic = "disk failure",
                        )
                    },
                    workspaceRoot = WorkspaceFilesystemRoot { root },
                )

            shouldThrow<RemoteSyncBoundaryFailure> { storeIo.refresh() }
                .code shouldBe "io_error"
        }

        test("observe stays idle without polling SAF and clears immediately on Direct to SAF switch") {
            val dispatcher = StandardTestDispatcher()
            runTest(dispatcher) {
                val mounts = MutableStateFlow(WorkspaceMount.Opening)
                val directStatus = sampleCycleStatus(cycleId = "cycle-direct", updatedAtMs = 99L)
                val remote =
                    RecordingStatusRemoteSyncRepository { root ->
                        root shouldBe "/ws/direct"
                        directStatus
                    }
                val store =
                    RustSyncCycleStatusStore(
                        remoteSync = remote,
                        workspaceRoot =
                        WorkspaceFilesystemRoot {
                            val mount = mounts.value
                            val raw = mount.location?.raw?.takeIf { mount.admitsProjectionReads }
                            raw?.takeUnless { it.startsWith("content:", ignoreCase = true) }
                        },
                        dispatcher = dispatcher,
                        workspaceMounts = mounts,
                    )
                val emissions = mutableListOf<RemoteSyncCycleStatus?>()
                val job = backgroundScope.launch(dispatcher) {
                    store.observe(pollIntervalMillis = 60_000L).collect(emissions::add)
                }

                runCurrent()
                emissions shouldBe listOf(null)
                remote.requestedRoots shouldBe emptyList()

                mounts.value =
                    WorkspaceMount(
                        readiness = EngineReadiness.Ready,
                        location = StorageLocation("/ws/direct"),
                        authority = WorkspaceAuthority("ws-1", 1L, 1uL),
                        freshness = ProjectionFreshness.Verified(1uL),
                    )
                runCurrent()
                emissions shouldBe listOf(null, directStatus)
                remote.requestedRoots shouldBe listOf("/ws/direct")

                val safUri = "content://com.android.externalstorage.documents/tree/primary%3ALomo"
                mounts.value =
                    WorkspaceMount(
                        readiness = EngineReadiness.Ready,
                        location = StorageLocation(safUri),
                        authority = WorkspaceAuthority("ws-2", 2L, 2uL),
                        freshness = ProjectionFreshness.Verified(2uL),
                    )
                runCurrent()
                emissions shouldBe listOf(null, directStatus, null)
                remote.requestedRoots shouldBe listOf("/ws/direct")

                job.cancel()
            }
        }
    }
}

private fun boundaryFailure(code: String): RemoteSyncBoundaryFailure = RemoteSyncBoundaryFailure(
    category = "validation",
    code = code,
    retryDisposition = "never",
    diagnostic = code,
)

private fun sampleCycleStatus(cycleId: String, updatedAtMs: Long): RemoteSyncCycleStatus = RemoteSyncCycleStatus(
    hasRecord = true,
    cycleSeq = 1L,
    cycleId = cycleId,
    fenceKey = "fence-1",
    backendKind = "git",
    sessionId = "sess-1",
    applyRemote = true,
    phase = RemoteSyncCycleStatus.PHASE_IDLE,
    stage = RemoteSyncCycleStatus.STAGE_FINISHED,
    ensurePresentCount = 0,
    ensureAbsentCount = 0,
    pullPresentCount = 0,
    openConflictCount = 0,
    holdCount = 0,
    localEntryCount = 0,
    remoteListedCount = 0,
    baselineEntryCount = 0,
    pagesApplied = 0,
    baselineAdvanced = false,
    retryDisposition = "never",
    failureCode = null,
    failureMessage = null,
    cancelRequested = false,
    startedAtMs = 1L,
    updatedAtMs = updatedAtMs,
    finishedAtMs = updatedAtMs,
    lastSuccessfulAtMs = updatedAtMs,
    stateStamp = 1L,
)

private class RecordingStatusRemoteSyncRepository(private val onCycleStatus: (String) -> RemoteSyncCycleStatus) :
    RemoteSyncRepository {
    val requestedRoots = mutableListOf<String>()

    override fun cycleStatus(workspaceRoot: String): RemoteSyncCycleStatus {
        requestedRoots += workspaceRoot
        return onCycleStatus(workspaceRoot)
    }

    override fun listConflicts(workspaceRoot: String, cursor: Int, limit: Int): RemoteSyncConflictPage = error("unused")

    override fun resolveConflicts(
        workspaceRoot: String,
        expectedRevision: Long,
        resolutions: List<RemoteSyncConflictResolution>,
    ): RemoteSyncConflictResolveResult = error("unused")

    override fun issueSecretLease(secretBytes: ByteArray, ttlMillis: Long): RemoteSyncSecretLease = error("unused")

    override fun probeSecretLease(leaseId: String): Int = error("unused")

    override fun revokeSecretLease(leaseId: String) = error("unused")

    override fun runCycle(request: RemoteSyncCycleRequest): RemoteSyncCyclePlanSummary = error("unused")

    override fun loadWorkspaceGeneration(workspaceRoot: String): String = error("unused")

    override fun resetControlTree(workspaceRoot: String) = error("unused")

    override fun requestCancel(workspaceRoot: String): RemoteSyncCycleStatus = error("unused")

    override fun probeBackend(request: RemoteSyncCycleRequest): RemoteSyncBackendProbe = error("unused")
}
