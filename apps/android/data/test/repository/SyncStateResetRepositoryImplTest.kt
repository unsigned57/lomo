package com.lomo.data.repository

/*
 * Behavior Contract:
 * - Unit under test: SyncStateResetRepositoryImpl.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: workspace-scoped sync reset clears Rust `.lomo/sync/v1` control records then the
 *   Kotlin pending-review table; a missing Direct root still clears the client table.
 *
 * Scenarios:
 * - Given a Direct workspace root and a pending-review record, when reset runs, then
 *   RemoteSyncRepository.resetControlTree receives that root and the table is empty.
 * - Given no Direct workspace root, when reset runs, then the table is cleared and the remote
 *   reset is not called.
 *
 * Observable outcomes: last reset root; pending-review lookups after clearAll.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.repository.SyncStateResetRepositoryImplTest'
 * - RED: impl only called pendingReviewTable.clearAll().
 *
 * Excludes: JNI / control-tree file removal (native sync_ffi_contract).
 * Test Change Justification:
 * - Reason category: product/domain contract changed.
 * - Old behavior/assertion being replaced: reset behavior asserted against the prior sync control surface.
 * - Why old assertion is no longer correct: the reset path now runs through the surviving sync-session bridge.
 * - Coverage preserved by: the same reset assertions plus the new control-tree cases.
 * - Why this is not fitting the test to the implementation: it pins the live reset contract after dead-surface deletion.
 */

import com.lomo.data.engine.media.WorkspaceFilesystemRoot
import com.lomo.data.engine.sync.RemoteSyncConflictPage
import com.lomo.data.engine.sync.RemoteSyncConflictResolveResult
import com.lomo.data.engine.sync.RemoteSyncConflictResolution
import com.lomo.data.engine.sync.RemoteSyncCyclePlanSummary
import com.lomo.data.engine.sync.RemoteSyncCycleRequest
import com.lomo.data.engine.sync.RemoteSyncBackendProbe
import com.lomo.data.engine.sync.RemoteSyncCycleStatus
import com.lomo.data.engine.sync.RemoteSyncRepository
import com.lomo.data.engine.sync.RemoteSyncSecretLease
import com.lomo.data.sync.pendingreview.PendingReviewTable
import com.lomo.data.sync.pendingreview.PendingSyncReviewRecord
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

private class MemoryPendingReviewTable : PendingReviewTable {
    private val records = mutableMapOf<String, PendingSyncReviewRecord>()

    override suspend fun getByBackend(
        backend: String,
        workspaceGeneration: String,
    ): PendingSyncReviewRecord? = records["$workspaceGeneration|$backend"]

    override suspend fun upsert(record: PendingSyncReviewRecord) {
        records["${record.workspaceGeneration}|${record.backend}"] = record
    }

    override suspend fun deleteByBackend(
        backend: String,
        workspaceGeneration: String,
    ) {
        records.remove("$workspaceGeneration|$backend")
    }

    override suspend fun clearAll() {
        records.clear()
    }
}

private class RecordingResetRemoteSync : RemoteSyncRepository {
    var lastResetRoot: String? = null
    var resetCount: Int = 0

    override fun listConflicts(
        workspaceRoot: String,
        cursor: Int,
        limit: Int,
    ): RemoteSyncConflictPage = error("unused")

    override fun resolveConflicts(
        workspaceRoot: String,
        expectedRevision: Long,
        resolutions: List<RemoteSyncConflictResolution>,
    ): RemoteSyncConflictResolveResult = error("unused")

    override fun issueSecretLease(
        secretBytes: ByteArray,
        ttlMillis: Long,
    ): RemoteSyncSecretLease = error("unused")

    override fun probeSecretLease(leaseId: String): Int = error("unused")

    override fun revokeSecretLease(leaseId: String) = error("unused")

    override fun runCycle(request: RemoteSyncCycleRequest): RemoteSyncCyclePlanSummary = error("unused")

    override fun loadWorkspaceGeneration(workspaceRoot: String): String = error("unused")

    override fun resetControlTree(workspaceRoot: String) {
        lastResetRoot = workspaceRoot
        resetCount += 1
    }
    var nextCycleStatus: RemoteSyncCycleStatus = noRecordCycleStatus()

    override fun cycleStatus(workspaceRoot: String): RemoteSyncCycleStatus = nextCycleStatus

    override fun requestCancel(workspaceRoot: String): RemoteSyncCycleStatus = nextCycleStatus

    override fun probeBackend(request: RemoteSyncCycleRequest): RemoteSyncBackendProbe =
        error("probe not used by this unit")

}

class SyncStateResetRepositoryImplTest : FunSpec({
    test("reset clears Rust control tree then pending-review records") {
        runTest {
            val table = MemoryPendingReviewTable()
            table.upsert(
                PendingSyncReviewRecord(
                    workspaceGeneration = "gen-1",
                    backend = "GIT",
                    reviewKind = "SYNC_INBOX_IMPORT_REVIEW",
                    timestamp = 1L,
                    payloadJson = "{}",
                ),
            )
            val remote = RecordingResetRemoteSync()
            val repository =
                SyncStateResetRepositoryImpl(
                    pendingReviewTable = table,
                    workspaceRoot = WorkspaceFilesystemRoot { "/workspaces/notes" },
                    remoteSync = remote,
                )

            repository.resetWorkspaceScopedSyncState()

            remote.resetCount shouldBe 1
            remote.lastResetRoot shouldBe "/workspaces/notes"
            table.getByBackend(backend = "GIT", workspaceGeneration = "gen-1").shouldBeNull()
        }
    }

    test("missing Direct root still clears pending review without calling Rust reset") {
        runTest {
            val table = MemoryPendingReviewTable()
            table.upsert(
                PendingSyncReviewRecord(
                    workspaceGeneration = "gen-1",
                    backend = "GIT",
                    reviewKind = "SYNC_INBOX_IMPORT_REVIEW",
                    timestamp = 1L,
                    payloadJson = "{}",
                ),
            )
            val remote = RecordingResetRemoteSync()
            val repository =
                SyncStateResetRepositoryImpl(
                    pendingReviewTable = table,
                    workspaceRoot = WorkspaceFilesystemRoot { null },
                    remoteSync = remote,
                )

            repository.resetWorkspaceScopedSyncState()

            remote.resetCount shouldBe 0
            remote.lastResetRoot.shouldBeNull()
            table.getByBackend(backend = "GIT", workspaceGeneration = "gen-1").shouldBeNull()
        }
    }
})

private fun noRecordCycleStatus(): RemoteSyncCycleStatus =
    RemoteSyncCycleStatus(
        hasRecord = false,
        cycleSeq = 0L,
        cycleId = "",
        fenceKey = "",
        backendKind = "",
        sessionId = "",
        applyRemote = false,
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
        startedAtMs = 0L,
        updatedAtMs = 0L,
        finishedAtMs = null,
        lastSuccessfulAtMs = null,
        stateStamp = 0L,
    )
