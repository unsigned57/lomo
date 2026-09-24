package com.lomo.data.engine.sync

/*
 * Behavior Contract:
 * - Unit under test: RemoteSyncCycleStateMapping (durable cycle_state.rec → provider/Sync Center)
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: the durable Rust cycle record is the sole sync-status authority — Kotlin maps
 *   record fields verbatim and never invents timestamps, counts, or completion.
 *
 * Scenarios:
 * - Given null/no-record status, when mapped, then provider state is Idle and session is Idle
 *   with canCancel=false.
 * - Given a running record on a non-apply stage, when mapped, then Running(LISTING) / session Plan.
 * - Given a running record on the applying stage, when mapped, then Running(COMMITTING) /
 *   session Apply with canCancel=true.
 * - Given a running record with a persisted cancel request, when mapped, then session is
 *   Cancelling and canCancel=false.
 * - Given a completed record, when mapped, then Success carries finishedAtMs and a count-built
 *   summary; open conflicts project the session as ConflictOpen.
 * - Given a failed record, when mapped, then Error carries the durable code/message/timestamp.
 * - Given a cancelled record, when mapped, then Error code is sync_cycle_cancelled and session
 *   is Cancelled.
 *
 * Observable outcomes: UnifiedSyncState / WebDavSyncState / S3SyncState /
 * RemoteSyncSessionProgress fields.
 * TDD proof:
 * - Fails before the fix because the cycle-state mapping surface does not exist.
 * Excludes: real JNI and WorkManager (covered by native/data worker contracts).
 */

import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.RemoteSyncSessionPhase
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.UnifiedSyncPhase
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.model.WebDavSyncState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class RemoteSyncCycleStateMappingTest : DataFunSpec() {
    init {
        test("missing record maps to Idle across providers and session") {
            val status: RemoteSyncCycleStatus? = null

            status.toUnifiedSyncState(SyncBackendType.GIT) shouldBe UnifiedSyncState.Idle
            status.toWebDavSyncState() shouldBe WebDavSyncState.Idle
            status.toS3SyncState() shouldBe S3SyncState.Idle

            noRecord().toUnifiedSyncState(SyncBackendType.GIT) shouldBe UnifiedSyncState.Idle
            val session = status.toSessionProgress()
            session.phase shouldBe RemoteSyncSessionPhase.Idle
            session.canCancel shouldBe false
        }

        test("running non-apply stage maps to Running(LISTING) and session Plan") {
            val status = record(phase = RemoteSyncCycleStatus.PHASE_RUNNING)

            status.toUnifiedSyncState(SyncBackendType.GIT) shouldBe
                UnifiedSyncState.Running(SyncBackendType.GIT, UnifiedSyncPhase.LISTING)
            status.toWebDavSyncState() shouldBe WebDavSyncState.Listing
            status.toS3SyncState() shouldBe S3SyncState.Listing

            val session = status.toSessionProgress()
            session.phase shouldBe RemoteSyncSessionPhase.Plan
            session.canCancel shouldBe true
        }

        test("running applying stage maps to Running(COMMITTING) and session Apply") {
            val status =
                record(
                    phase = RemoteSyncCycleStatus.PHASE_RUNNING,
                    stage = RemoteSyncCycleStatus.STAGE_APPLYING,
                    pagesApplied = 3,
                )

            status.toUnifiedSyncState(SyncBackendType.GIT) shouldBe
                UnifiedSyncState.Running(SyncBackendType.GIT, UnifiedSyncPhase.COMMITTING)
            status.toWebDavSyncState() shouldBe WebDavSyncState.Uploading
            status.toS3SyncState() shouldBe S3SyncState.Uploading

            val session = status.toSessionProgress()
            session.phase shouldBe RemoteSyncSessionPhase.Apply
            session.completedActions shouldBe 3
            session.canCancel shouldBe true
        }

        test("running record with persisted cancel request maps to Cancelling without cancel affordance") {
            val status =
                record(
                    phase = RemoteSyncCycleStatus.PHASE_RUNNING,
                    cancelRequested = true,
                )

            val session = status.toSessionProgress()
            session.phase shouldBe RemoteSyncSessionPhase.Cancelling
            session.canCancel shouldBe false
        }

        test("completed record maps to Success with durable timestamp and real counts") {
            val status =
                record(
                    phase = RemoteSyncCycleStatus.PHASE_COMPLETED,
                    ensurePresentCount = 4,
                    ensureAbsentCount = 1,
                    pullPresentCount = 2,
                    openConflictCount = 0,
                    pagesApplied = 2,
                    finishedAtMs = 1_700_000_000_000L,
                )

            val unified = status.toUnifiedSyncState(SyncBackendType.GIT)
            unified.shouldBeInstanceOf<UnifiedSyncState.Success>()
            unified.timestamp shouldBe 1_700_000_000_000L
            unified.summary shouldBe "ensure=5 pull=2 conflicts=0 pages=2"

            status.toSessionProgress().phase shouldBe RemoteSyncSessionPhase.Completed
        }

        test("completed record with open conflicts projects session ConflictOpen") {
            val status =
                record(
                    phase = RemoteSyncCycleStatus.PHASE_COMPLETED,
                    openConflictCount = 2,
                )

            status.toSessionProgress().phase shouldBe RemoteSyncSessionPhase.ConflictOpen
        }

        test("failed record maps to Error preserving durable code message and timestamp") {
            val status =
                record(
                    phase = RemoteSyncCycleStatus.PHASE_FAILED,
                    failureCode = "remote_unreachable",
                    failureMessage = "endpoint refused connection",
                    finishedAtMs = 42L,
                )

            val unified = status.toUnifiedSyncState(SyncBackendType.WEBDAV)
            unified.shouldBeInstanceOf<UnifiedSyncState.Error>()
            unified.error.providerCode shouldBe "remote_unreachable"
            unified.error.message shouldBe "endpoint refused connection"
            unified.timestamp shouldBe 42L

            status.toSessionProgress().phase shouldBe RemoteSyncSessionPhase.Failed
        }

        test("cancelled record maps to cancellation-coded Error and session Cancelled") {
            val status =
                record(
                    phase = RemoteSyncCycleStatus.PHASE_CANCELLED,
                    finishedAtMs = 77L,
                )

            val unified = status.toUnifiedSyncState(SyncBackendType.S3)
            unified.shouldBeInstanceOf<UnifiedSyncState.Error>()
            unified.error.providerCode shouldBe "sync_cycle_cancelled"
            unified.error.message shouldBe "sync cycle cycle-000007 cancelled"
            unified.timestamp shouldBe 77L

            val session = status.toSessionProgress()
            session.phase shouldBe RemoteSyncSessionPhase.Cancelled
            session.canCancel shouldBe false
        }
    }
}

private fun noRecord(): RemoteSyncCycleStatus =
    record(phase = RemoteSyncCycleStatus.PHASE_IDLE).copy(hasRecord = false)

private fun record(
    phase: String,
    stage: String = RemoteSyncCycleStatus.STAGE_PLANNING,
    ensurePresentCount: Int = 0,
    ensureAbsentCount: Int = 0,
    pullPresentCount: Int = 0,
    openConflictCount: Int = 0,
    pagesApplied: Int = 0,
    failureCode: String? = null,
    failureMessage: String? = null,
    cancelRequested: Boolean = false,
    finishedAtMs: Long? = null,
): RemoteSyncCycleStatus =
    RemoteSyncCycleStatus(
        hasRecord = true,
        cycleSeq = 7L,
        cycleId = "cycle-000007",
        fenceKey = "fence",
        backendKind = "git",
        sessionId = "session",
        applyRemote = true,
        phase = phase,
        stage = stage,
        ensurePresentCount = ensurePresentCount,
        ensureAbsentCount = ensureAbsentCount,
        pullPresentCount = pullPresentCount,
        openConflictCount = openConflictCount,
        holdCount = 0,
        localEntryCount = 0,
        remoteListedCount = 0,
        baselineEntryCount = 0,
        pagesApplied = pagesApplied,
        baselineAdvanced = phase == RemoteSyncCycleStatus.PHASE_COMPLETED,
        retryDisposition = "never",
        failureCode = failureCode,
        failureMessage = failureMessage,
        cancelRequested = cancelRequested,
        startedAtMs = 10L,
        updatedAtMs = 20L,
        finishedAtMs = finishedAtMs,
        lastSuccessfulAtMs = null,
        stateStamp = 9L,
    )
