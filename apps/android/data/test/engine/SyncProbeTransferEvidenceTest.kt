package com.lomo.data.engine

import com.lomo.data.engine.sync.RemoteSyncCyclePlanSummary
import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Capability: require nonempty verified transfer evidence for device sync probes; data diagnostic; P1.
 * Scenarios:
 * - Given a populated corpus and completed upload/idle cycles, when validated, then uploads are counted.
 * - Given an empty snapshot, missing cycle, held transfer, or zero uploads, when validated, then evidence is rejected.
 * Observable outcomes: upload count or explicit diagnostic exception.
 * TDD proof: before-device-perf.json reports success with localEntryCount=0 and no PUT requests;
 * sync-red.log reproduces the empty-snapshot cause; sync-green.log fixes the session boundary.
 * Evidence: target/lomo/reports/manual/media-toolbar-search/.
 * Excludes: network implementation, credentials and download/import behavior.
 */
class SyncProbeTransferEvidenceTest : DataFunSpec() {
    init {
        test("given uploaded and idle cycles when verified then real upload work is counted") {
            requireSyncTransferEvidence(3, 2, listOf(cycle(3), cycle(0))) shouldBe 3L
        }
        test("given an empty local snapshot when verified then the probe refuses a false success") {
            shouldThrow<IllegalStateException> {
                requireSyncTransferEvidence(0, 1, listOf(cycle(0)))
            }.message shouldBe "Sync probe saw no local documents in the configured corpus"
        }
        test("given a failed cycle when verified then partial samples are rejected") {
            shouldThrow<IllegalStateException> {
                requireSyncTransferEvidence(3, 2, listOf(cycle(3)))
            }.message shouldBe "Sync probe did not complete every requested cycle"
        }
        test("given a held transfer when verified then a planned upload cannot count as verified") {
            shouldThrow<IllegalStateException> {
                requireSyncTransferEvidence(3, 1, listOf(cycle(3).copy(holdCount = 1)))
            }.message shouldBe "Sync probe contains held, conflicted or unverified transfers"
        }
        test("given only idle cycles when verified then upload evidence is rejected") {
            shouldThrow<IllegalStateException> {
                requireSyncTransferEvidence(3, 1, listOf(cycle(0)))
            }.message shouldBe "Sync probe completed without uploading any corpus document"
        }
    }
}

private fun cycle(uploads: Int): RemoteSyncCyclePlanSummary = RemoteSyncCyclePlanSummary(
    sessionId = "fixture", sessionKind = "first_takeover", sessionRevision = 1,
    baselineEstablished = false, ensurePresentCount = uploads, ensureAbsentCount = 0,
    pullPresentCount = 0, openConflictCount = 0, holdCount = 0, openConflictPaths = 0,
    conflictRevision = null, retryDisposition = "after_user_action",
)
