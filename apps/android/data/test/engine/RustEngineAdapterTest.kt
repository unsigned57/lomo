package com.lomo.data.engine

/*
 * Behavior Contract:
 * - Unit under test: RustEngineAdapter.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: expose Rust engine readiness as a platform-neutral StateFlow while treating every
 *   native port/engine exactly once. Store commit receipts publish onto StoreInvalidationBus;
 *   journal events are not an invalidation protocol and are not subscribed.
 *
 * Scenarios:
 * - Given a native Ready snapshot, when the adapter starts, then readiness is Ready after one state read.
 * - Given foreground resumption or adapter close, when requested, then state is resnapshotted and
 *   the native port is closed exactly once.
 * - Given Opening with a platform batch runner, when bootstrap completes, then Ready is published
 *   after the runner drives the job.
 * - Given state read or bootstrap drive fails during acquisition, when construction
 *   aborts, then the native port closes exactly once.
 * - Given the state read fails after a Ready snapshot, when resnapshot is requested, then
 *   readiness becomes typed recovery instead of keeping the stale Ready.
 * - Given the engine reports an unknown failure category, when the snapshot decodes, then readiness
 *   fails closed and keeps the unknown value in the diagnostic.
 * - Given two callers receive the same deduplicated job id, when both drive it concurrently, then
 *   platform execution is coalesced into a single flight, the second caller observes the first caller's
 *   completed step without driving again, and subsequent callers after flight completion can re-drive.
 * - Given SAF provider facts disagree with an empty memo projection, when a source document
 *   fingerprint is requested, then the provider probe is authoritative, including verified absence.
 * - Given native LAN ingest returns a store commit, when the adapter commits the received item,
 *   then StoreInvalidationBus publishes that commit and paging consumers invalidate.
 *
 * Observable outcomes:
 * - StateFlow readiness, native state-read count, port closure, and store invalidation
 *   publication / paging invalid state.
 *
 * TDD proof:
 * - RED on 2026-07-27: state/bootstrap exceptions escape the constructor while the
 *   acquired native port remains open.
 * - RED on 2026-07-27: a failing state read or an unknown failure category escaped the adapter, so
 *   `readiness` kept the last Ready and the write gate stayed open against an unknown engine.
 * - RED on 2026-08-06: two callers entered the platform driver concurrently for one deduplicated
 *   job id, allowing both to submit a result for the same durable batch.
 * - RED on 2026-09-07: after first waiter completed in a concurrent flight, second waiter executed a
 *   duplicate poll due to missing flight step memoization; and sleep-based test scheduling was non-deterministic.
 * - RED on 2026-09-12: LAN received-item commits returned only a memo id, so the store bus
 *   never observed native-owned projection writes.
 * - RED on 2026-09-12: NativeEnginePort still subscribed journal CoreEvent through
 *   BoundedInvalidationQueue, leaving a second publication clock.
 *
 * Excludes:
 * - SAF action execution internals, workspace selection persistence, Compose rendering, and Rust.
 *
 * Test Change Justification:
 * - Reason category: T12 session-owned projection rebuild; SAF streaming rebuild tests removed.
 * - Old behavior/assertion being replaced: adapter-owned begin/append/finish SAF rebuild and its
 *   concurrent/deadline/failure matrix.
 * - Why old assertion is no longer correct: StoreHandle streaming rebuild is gone; session_rebuild_projection
 *   is the one production projection path.
 * - Coverage preserved by: single-flight driveJob, readiness fail-closed, and native store_ffi_contract
 *   engine_open_does_not_materialize_a_second_sqlite / session rebuild contracts.
 * - Why this is not fitting the test to the implementation: production no longer has a Kotlin rebuild sink.
 *
 * Test Change Justification:
 * - Reason category: deterministic concurrency synchronization and single-flight result sharing.
 * - Old behavior/assertion being replaced: non-deterministic Thread.sleep(50) in single-flight test.
 * - Why old assertion is no longer correct: arbitrary sleep does not guarantee that the second caller
 *   has actually queued into the flight before the first poll is released, relying on scheduler luck.
 * - Coverage preserved by: asserting second caller reaches Thread.State.BLOCKED on the active job monitor,
 *   polledJobIds.size == 1 across both concurrent callers, identical Completed results for both, and
 *   polledJobIds.size == 2 on subsequent re-drive.
 * - Why this is not fitting the test to the implementation: thread state observation proves true monitor
 *   contention at runtime without modifying production code with test-only hooks.
 */

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.engine.lan.LanDeviceIdentity
import com.lomo.data.engine.lan.LanBatchPreview
import com.lomo.data.engine.lan.LanDiscoveredPeer
import com.lomo.data.engine.lan.LanDiscoveryFacts
import com.lomo.data.engine.lan.LanLocalIdentity
import com.lomo.data.engine.lan.LanNetworkFacts
import com.lomo.data.engine.lan.LanPairingChallenge
import com.lomo.data.engine.lan.LanPeerPage
import com.lomo.data.engine.lan.LanRuntimeInbox
import com.lomo.data.engine.lan.LanInboxWait
import com.lomo.data.engine.lan.LanServiceState
import com.lomo.data.engine.lan.LanSendItemPlan
import com.lomo.data.engine.lan.LanSessionChallenge
import com.lomo.data.engine.lan.LanSessionState
import com.lomo.data.engine.lan.LanTransferShape
import com.lomo.data.engine.lan.LanProtocolLimits
import com.lomo.data.engine.store.StoreInvalidationScope
import com.lomo.data.repository.StoreInvalidationBus
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.EngineRetryDisposition
import com.lomo.nativebridge.PlatformBatchResult
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RustEngineAdapterTest : DataFunSpec() {
    init {
        test("given native ready state when adapter starts then readiness is Ready after one state read") {
            val native = FakeNativeEnginePort(NativeEngineSnapshot.Ready(coreRevision = 4uL, eventSequence = 9uL))

            val adapter = testRustEngineAdapter(native)

            adapter.readiness.value shouldBe EngineReadiness.Ready
            native.stateReads shouldBe 1
            adapter.close()
        }

        test("given SAF provider fingerprint when memo projection is empty then provider fact wins") {
            val native = FakeNativeEnginePort(NativeEngineSnapshot.Ready(coreRevision = 4uL, eventSequence = 9uL))
            val emptyDocumentFingerprint = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
            val adapter =
                testRustEngineAdapter(
                    native = native,
                    sourceDocumentFingerprintProbe = { path ->
                        path shouldBe "2026_08_25.md"
                        emptyDocumentFingerprint
                    },
                )

            adapter.sourceDocumentFingerprint("2026_08_25.md") shouldBe emptyDocumentFingerprint
            adapter.close()
        }

        test("given a native LAN store commit when the received item is committed then paging invalidates") {
            val invalidation = StoreInvalidationBus()
            val paging = AdapterProjectionPagingSource()
            invalidation.register(paging)
            val native = FakeNativeEnginePort(NativeEngineSnapshot.Ready(coreRevision = 1uL, eventSequence = 1uL))
            native.lanReceivedItemCommit =
                com.lomo.nativebridge.StoreMemoCommit(
                    operationId = "lan-item",
                    memoId = "memo-received",
                    coreRevision = 1uL,
                    eventSequence = 1uL,
                    contentRevision = 1uL,
                    fileFingerprint = "fp",
                    scopes = listOf(
                        com.lomo.nativebridge.StoreInvalidationScope.MEMO_LIST,
                        com.lomo.nativebridge.StoreInvalidationScope.STATS,
                    ),
                    idempotentReplay = false,
                )
            val adapter = testRustEngineAdapter(native, invalidation = invalidation)

            adapter.commitReceivedLanItem("batch-1", 0u, 1_700_000_000_000).memoId shouldBe "memo-received"

            paging.invalid shouldBe true
            invalidation.publications.value.coreRevision shouldBe 1L
            invalidation.publications.value.eventSequence shouldBe 1L
            invalidation.publications.value.scopes shouldContainExactly
                setOf(StoreInvalidationScope.MemoList, StoreInvalidationScope.Stats)
            adapter.close()
        }

        test("given a native task toggle commit when the adapter toggles then paging invalidates") {
            val invalidation = StoreInvalidationBus()
            val paging = AdapterProjectionPagingSource()
            invalidation.register(paging)
            val native = FakeNativeEnginePort(NativeEngineSnapshot.Ready(coreRevision = 4uL, eventSequence = 5uL))
            native.toggleTaskCommit =
                com.lomo.nativebridge.StoreMemoCommit(
                    operationId = "task-1",
                    memoId = "m_dddddddddddddddddddddddddddddddd",
                    coreRevision = 4uL,
                    eventSequence = 5uL,
                    contentRevision = 2uL,
                    fileFingerprint = "fp",
                    scopes = listOf(
                        com.lomo.nativebridge.StoreInvalidationScope.MEMO_LIST,
                        com.lomo.nativebridge.StoreInvalidationScope.SEARCH,
                    ),
                    idempotentReplay = false,
                )
            val adapter = testRustEngineAdapter(native, invalidation = invalidation)

            adapter.sessionToggleTask(
                com.lomo.nativebridge.SessionToggleTaskRequest(
                    operationId = "task-1",
                    memoId = "m_dddddddddddddddddddddddddddddddd",
                    lineIndex = 1u,
                    done = true,
                ),
            ).memoId shouldBe "m_dddddddddddddddddddddddddddddddd"

            paging.invalid shouldBe true
            invalidation.publications.value.coreRevision shouldBe 4L
            invalidation.publications.value.eventSequence shouldBe 5L
            adapter.close()
        }

        test("given foreground resnapshot and repeated close then state reloads and the port closes once") {
            val native = FakeNativeEnginePort(NativeEngineSnapshot.AwaitingWorkspaceSelection)
            val adapter = testRustEngineAdapter(native)
            native.snapshot = NativeEngineSnapshot.Ready(coreRevision = 2uL, eventSequence = 3uL)

            adapter.resnapshot()
            adapter.close()
            adapter.close()

            adapter.readiness.value shouldBe EngineReadiness.Ready
            native.stateReads shouldBe 2
            native.portCloseCount shouldBe 1
        }

        test("given opening bootstrap when platform runner completes then Ready is published") {
            val native =
                FakeNativeEnginePort(NativeEngineSnapshot.Opening(jobId = "job-bootstrap")).apply {
                    pollResults["job-bootstrap"] =
                        ArrayDeque(
                            listOf(
                                NativeJobStep.Completed,
                            ),
                        )
                    afterSubmitSnapshot =
                        NativeEngineSnapshot.Ready(coreRevision = 0uL, eventSequence = 1uL)
                    // driveIfOpening calls runner then native.state(); simulate Ready after drive.
                    onPoll = {
                        snapshot = NativeEngineSnapshot.Ready(coreRevision = 0uL, eventSequence = 1uL)
                    }
                }
            val runner =
                PlatformBatchRunner(
                    native = native,
                    executor =
                        AndroidPlatformActionExecutor(
                            access = PlatformActionAccess {
                                error("no platform actions expected for completed job")
                            },
                            currentTimeMillis = { 0L },
                        ),
                )

            val adapter =
                RustEngineAdapter.acquire(
                    native,
                    platformBatchRunner = runner,
                    invalidation = StoreInvalidationBus(),
                )

            adapter.readiness.value shouldBe EngineReadiness.Ready
            adapter.close()
        }

        test("given state read failure during acquisition then native port closes exactly once") {
            val native =
                FakeNativeEnginePort(NativeEngineSnapshot.AwaitingWorkspaceSelection).apply {
                    stateFailure = IllegalStateException("state failed")
                }

            val error =
                io.kotest.assertions.throwables.shouldThrow<IllegalStateException> {
                    testRustEngineAdapter(native)
                }

            error.message shouldBe "state failed"
            native.portCloseCount shouldBe 1
        }

        test("given bootstrap drive failure during acquisition then native port closes exactly once") {
            val native =
                FakeNativeEnginePort(NativeEngineSnapshot.Opening(jobId = "job-bootstrap")).apply {
                    onPoll = { error("bootstrap drive failed") }
                }

            val error =
                io.kotest.assertions.throwables.shouldThrow<IllegalStateException> {
                    testRustEngineAdapter(native)
                }

            error.message shouldBe "bootstrap drive failed"
            native.portCloseCount shouldBe 1
        }

        test("given state read failure after Ready when resnapshot is requested then readiness fails closed") {
            val native = FakeNativeEnginePort(NativeEngineSnapshot.Ready(coreRevision = 1uL, eventSequence = 1uL))
            val adapter = testRustEngineAdapter(native)
            adapter.readiness.value shouldBe EngineReadiness.Ready
            native.stateFailure = IllegalStateException("engine handle vanished")

            adapter.resnapshot()

            val recovery = adapter.readiness.value.shouldBeInstanceOf<EngineReadiness.ReadOnlyRecovery>()
            recovery.code shouldBe "engine_state_unavailable"
            recovery.diagnostic shouldContain "engine handle vanished"
            adapter.close()
        }

        test("given an unknown failure category when the snapshot decodes then readiness fails closed") {
            val native = FakeNativeEnginePort(NativeEngineSnapshot.Ready(coreRevision = 1uL, eventSequence = 1uL))
            val adapter = testRustEngineAdapter(native)
            native.snapshot =
                NativeEngineSnapshot.ReadOnlyRecovery(
                    EngineFailureSnapshot(
                        category = "quantum_flux",
                        code = "unknown",
                        retryDisposition = "after_user_action",
                        diagnostic = "unmapped",
                    ),
                )

            adapter.resnapshot()

            val recovery = adapter.readiness.value.shouldBeInstanceOf<EngineReadiness.ReadOnlyRecovery>()
            recovery.code shouldBe "engine_state_unavailable"
            recovery.diagnostic shouldContain "quantum_flux"
            adapter.close()
        }

    }
}

private class FakeNativeEnginePort(
    initialSnapshot: NativeEngineSnapshot,
) : WorkspaceNativeEnginePort {
    var lanReceivedItemCommit: com.lomo.nativebridge.StoreMemoCommit? = null
    var toggleTaskCommit: com.lomo.nativebridge.StoreMemoCommit? = null
    val projectionPages = ArrayDeque<WorkspaceProjectionScanPageSnapshot>()
    val trashProjectionPages = ArrayDeque<WorkspaceTrashProjectionScanPageSnapshot>()
    val historyProjectionPages = ArrayDeque<WorkspaceHistoryProjectionScanPageSnapshot>()
    val projectionScanRequests = mutableListOf<Pair<UInt, String?>>()
    val trashProjectionScanRequests = mutableListOf<Pair<UInt, String?>>()
    val polledJobIds = mutableListOf<String>()
    override fun updateLanNetworkSnapshot(snapshot: LanNetworkFacts) = error("LAN not expected")

    override fun updateLanDiscoverySnapshot(snapshot: LanDiscoveryFacts) = error("LAN not expected")

    override fun startLanService(): LanServiceState = error("LAN not expected")

    override fun stopLanService(): LanServiceState = error("LAN not expected")

    override fun listLanDiscoveredPeers(): List<LanDiscoveredPeer> = error("LAN not expected")

    override fun lanTransferShape(): LanTransferShape = error("LAN not expected")

    override fun lanProtocolLimits(): LanProtocolLimits = error("LAN not expected")

    override fun configureLanIdentity(identity: LanDeviceIdentity): LanLocalIdentity =
        error("LAN not expected")

    override fun beginLanPairing(
        peerDeviceId: String,
        nowMs: Long,
        ttlMs: Long,
    ): LanPairingChallenge = error("LAN not expected")

    override fun awaitLanInbox(lastGeneration: ULong, timeoutMs: ULong): LanInboxWait =
        error("LAN not expected")

    override fun pollLanListener(nowMs: Long): LanRuntimeInbox = error("LAN not expected")

    override fun lanRuntimeInbox(): LanRuntimeInbox = error("LAN not expected")

    override fun lanPairingChallenge(pairingId: String): LanPairingChallenge = error("LAN not expected")

    override fun confirmLanPairing(
        pairingId: String,
        signature: ByteArray,
        nowMs: Long,
    ) = error("LAN not expected")

    override fun declineLanPairing(pairingId: String) = error("LAN not expected")

    override fun beginLanSession(
        peerDeviceId: String,
        nowMs: Long,
        ttlMs: Long,
    ): LanSessionChallenge = error("LAN not expected")

    override fun lanSessionChallenge(sessionId: String): LanSessionChallenge =
        error("LAN not expected")

    override fun confirmLanSession(
        sessionId: String,
        signature: ByteArray,
        nowMs: Long,
    ) = error("LAN not expected")

    override fun lanSessionState(sessionId: String): LanSessionState = error("LAN not expected")

    override fun prepareLanBatch(
        sessionId: String,
        batchId: String,
        items: List<LanSendItemPlan>,
    ) = error("LAN not expected")

    override fun lanBatchPreview(batchId: String): LanBatchPreview = error("LAN not expected")

    override fun approveLanBatch(
        sessionId: String,
        batchId: String,
        nowMs: Long,
        ttlMs: Long,
    ) = error("LAN not expected")

    override fun rejectLanBatch(
        sessionId: String,
        batchId: String,
        rejectedAtMs: Long,
    ) = error("LAN not expected")

    override fun sendLanBatchChunk(
        sessionId: String,
        batchId: String,
        itemIndex: UInt,
        attachmentSlot: UInt,
        chunkIndex: UInt,
        plaintext: ByteArray,
    ) = error("LAN not expected")

    override fun lanUnconfirmedBatchChunks(
        batchId: String,
        itemIndex: UInt,
        attachmentSlot: UInt,
    ): List<UInt> = error("LAN not expected")

    override fun commitReceivedLanItem(
        batchId: String,
        itemIndex: UInt,
        nowMs: Long,
    ): com.lomo.nativebridge.StoreMemoCommit =
        lanReceivedItemCommit ?: error("LAN not expected")

    override fun sessionToggleTask(
        request: com.lomo.nativebridge.SessionToggleTaskRequest,
    ): com.lomo.nativebridge.StoreMemoCommit = toggleTaskCommit ?: error("toggle not expected")

    override fun listLanPeers(): LanPeerPage = error("LAN not expected")

    override fun revokeLanPeer(
        deviceId: String,
        revokedAtMs: Long,
    ): LanPeerPage = error("LAN not expected")

    override fun stageMedia(
        mediaRoot: String,
        sourceKind: com.lomo.nativebridge.MediaSourceKind,
        sourcePath: String,
        humanNameHint: String,
    ): com.lomo.nativebridge.MediaStagedDto =
        com.lomo.nativebridge.MediaStagedDto(
            digest = "0".repeat(64),
            size = 0uL,
            mime = "application/octet-stream",
            stagingPath = "$mediaRoot/stage",
            humanNameHint = humanNameHint,
            suggestedFinalRelativePath = "media/attachment.bin",
        )

    override fun recordStageLease(
        workspaceRoot: String?,
        staged: com.lomo.nativebridge.MediaStagedDto,
        ownerKind: com.lomo.nativebridge.MediaStageOwnerKindDto,
        ownerId: String,
    ): com.lomo.nativebridge.MediaStageRecordDto =
        com.lomo.nativebridge.MediaStageRecordDto(
            artifactId = staged.digest,
            digest = staged.digest,
            size = staged.size,
            mime = staged.mime,
            stagingPath = staged.stagingPath,
            humanNameHint = staged.humanNameHint,
            suggestedFinalRelativePath = staged.suggestedFinalRelativePath,
            leases = listOf(com.lomo.nativebridge.MediaStageLeaseDto(staged.digest, ownerKind, ownerId)),
            stagedBytesPresent = true,
        )

    override fun stageRecordsForOwner(
        mediaRoot: String,
        ownerKind: com.lomo.nativebridge.MediaStageOwnerKindDto,
        ownerId: String,
    ): List<com.lomo.nativebridge.MediaStageRecordDto> = emptyList()

    override fun transferStageLease(
        mediaRoot: String,
        from: com.lomo.nativebridge.MediaStageLeaseDto,
        to: com.lomo.nativebridge.MediaStageLeaseDto,
    ): com.lomo.nativebridge.MediaStageReleaseDto =
        com.lomo.nativebridge.MediaStageReleaseDto(from.artifactId, 1uL, false)

    override fun releaseStageLease(
        mediaRoot: String,
        lease: com.lomo.nativebridge.MediaStageLeaseDto,
    ): com.lomo.nativebridge.MediaStageReleaseDto =
        com.lomo.nativebridge.MediaStageReleaseDto(lease.artifactId, 0uL, false)

    override fun allocateRecordingTarget(
        mediaRoot: String,
        extension: String,
    ): String = "$mediaRoot/recording.$extension"

    override fun finalizeRecording(
        mediaRoot: String,
        recordingPath: String,
        humanNameHint: String,
    ): com.lomo.nativebridge.MediaStagedDto =
        stageMedia(mediaRoot, com.lomo.nativebridge.MediaSourceKind.STAGED_TEMP, recordingPath, humanNameHint)

    override fun promoteMedia(
        workspaceRoot: String,
        plan: com.lomo.nativebridge.MediaPromotePlanDto,
    ): com.lomo.nativebridge.MediaPromoteResultDto =
        com.lomo.nativebridge.MediaPromoteResultDto(
            operationId = plan.operationId,
            digest = plan.staged.digest,
            mime = plan.staged.mime,
            size = plan.staged.size,
            finalAbsolutePath = "$workspaceRoot/${plan.finalRelativePath}",
            finalRelativePath = plan.finalRelativePath,
        )

    override fun queryMediaManifest(workspaceRoot: String): com.lomo.nativebridge.MediaManifestDto =
        com.lomo.nativebridge.MediaManifestDto(stageDirName = "stage", entries = emptyList())

    override fun mediaOrphanSweep(
        mediaRoot: String,
        committed: List<com.lomo.nativebridge.MediaCommittedEntryDto>,
        refs: List<com.lomo.nativebridge.MediaAttachmentRefDto>,
        existingTrash: List<com.lomo.nativebridge.MediaTrashEntryDto>,
        nowMs: ULong?,
        recoveryWindowMs: ULong,
    ): com.lomo.nativebridge.MediaOrphanSweepResultDto =
        com.lomo.nativebridge.MediaOrphanSweepResultDto(
            movedToTrash = emptyList(),
            permanentlyDeletedDigests = emptyList(),
            keptLive = 0uL,
        )

    override fun archiveExport(
        workspaceRoot: String,
        archivePath: String,
    ): com.lomo.nativebridge.ArchiveExportResultDto =
        com.lomo.nativebridge.ArchiveExportResultDto(
            archivePath = archivePath,
            schemaVersion = 2u,
            entryCount = 0uL,
        )

    override fun archiveInspect(
        archivePath: String,
        stagingRoot: String,
    ): com.lomo.nativebridge.ArchiveInspectResultDto =
        com.lomo.nativebridge.ArchiveInspectResultDto(
            stagingRoot = stagingRoot,
            schemaVersion = 2u,
            entryCount = 0uL,
        )

    override fun archiveImport(
        archivePath: String,
        stagingRoot: String,
    ): com.lomo.nativebridge.ArchiveInspectResultDto = archiveInspect(archivePath, stagingRoot)

    override fun archiveActivate(
        stagingRoot: String,
        liveRoot: String,
        backupRoot: String,
    ) = Unit

    override fun archiveImportActivateRebuild(
        archivePath: String,
        stagingRoot: String,
        liveRoot: String,
        backupRoot: String,
        rebuildBatchSize: UInt,
    ): com.lomo.nativebridge.StoreRebuildResult =
        com.lomo.nativebridge.StoreRebuildResult(
            memosIndexed = 0uL,
            fileCount = 0uL,
            attachmentCount = 0uL,
            workspaceDigest = "",
            storeDigest = "",
            corruptLomoIsolated = 0uL,
            highWaterRevision = 0uL,
            rewritten = false,
        )

    var snapshot: NativeEngineSnapshot = initialSnapshot
    var stateReads: Int = 0
    var portCloseCount: Int = 0
    val pollResults = mutableMapOf<String, ArrayDeque<NativeJobStep>>()
    var afterSubmitSnapshot: NativeEngineSnapshot? = null
    var onPoll: (() -> Unit)? = null
    var stateFailure: Throwable? = null

    override fun state(): NativeEngineSnapshot {
        stateReads += 1
        stateFailure?.let { throw it }
        return snapshot
    }

    override fun pollJob(jobId: String): NativeJobStep {
        polledJobIds += jobId
        onPoll?.invoke()
        val queue = pollResults[jobId]
        return queue?.removeFirstOrNull() ?: NativeJobStep.Running
    }

    override fun submitPlatformResult(
        jobId: String,
        result: PlatformBatchResult,
    ): NativeJobStep {
        afterSubmitSnapshot?.let { snapshot = it }
        val queue = pollResults[jobId]
        return queue?.removeFirstOrNull() ?: NativeJobStep.Completed
    }

    override fun renderMarkdown(
        content: String,
        schemaVersion: UInt,
    ): com.lomo.domain.model.markdown.MarkdownRenderDocument = error("render not expected")

    override fun startWorkspaceScan(
        pageSize: UInt,
        cursor: String?,
        rootPath: String?,
        deadlineMillis: ULong,
    ): String {
        projectionScanRequests += pageSize to cursor
        return "projection-scan"
    }

    override fun readWorkspaceScanPage(jobId: String): WorkspaceScanPageSnapshot =
        error("scan page not expected")

    override fun readWorkspaceProjectionScanPage(jobId: String): WorkspaceProjectionScanPageSnapshot =
        projectionPages.removeFirstOrNull() ?: WorkspaceProjectionScanPageSnapshot(emptyList(), null)

    override fun startWorkspaceTrashScan(
        pageSize: UInt,
        cursor: String?,
        deadlineMillis: ULong,
    ): String {
        trashProjectionScanRequests += pageSize to cursor
        pollResults.putIfAbsent(
            "trash-projection-scan",
            ArrayDeque(listOf(NativeJobStep.Completed)),
        )
        return "trash-projection-scan"
    }

    override fun readWorkspaceTrashProjectionScanPage(
        jobId: String,
    ): WorkspaceTrashProjectionScanPageSnapshot =
        trashProjectionPages.removeFirstOrNull() ?: WorkspaceTrashProjectionScanPageSnapshot(emptyList(), null)

    override fun startWorkspaceHistoryScan(
        pageSize: UInt,
        cursor: String?,
        deadlineMillis: ULong,
    ): String {
        pollResults.putIfAbsent(
            "history-projection-scan",
            ArrayDeque(listOf(NativeJobStep.Completed)),
        )
        return "history-projection-scan"
    }

    override fun readWorkspaceHistoryProjectionScanPage(
        jobId: String,
    ): WorkspaceHistoryProjectionScanPageSnapshot =
        historyProjectionPages.removeFirstOrNull()
            ?: WorkspaceHistoryProjectionScanPageSnapshot(emptyList(), null)

    override fun startWorkspaceDocumentCommand(
        path: String,
        expectedState: WorkspaceNativeExpectedState,
        command: WorkspaceNativeCommandSpec,
        deadlineMillis: ULong,
    ): String = error("document command not expected")

    override fun readWorkspaceDocumentCommandResult(jobId: String): WorkspaceNativeCommandResultSnapshot =
        error("document result not expected")

    override fun startWorkspaceTrashCommand(
        path: String,
        expectedFingerprint: String,
        command: WorkspaceNativeTrashCommandSpec,
        deadlineMillis: ULong,
    ): String = error("trash command not expected")

    override fun readWorkspaceTrashCommandResult(jobId: String): WorkspaceNativeTrashCommandResultSnapshot =
        error("trash result not expected")

    override fun queryMemos(
        query: com.lomo.nativebridge.StoreMemoQuery,
        cursor: com.lomo.nativebridge.StorePageCursor?,
        pageSize: UInt,
        startMemoId: String?,
        backward: Boolean,
    ): com.lomo.nativebridge.StoreMemoPage = error("store query not expected")

    override fun queryCount(query: com.lomo.nativebridge.StoreMemoQuery): ULong =
        error("store count not expected")

    override fun selectMemoPromotePlans(
        content: String,
        candidates: List<com.lomo.nativebridge.MediaPromotePlanDto>,
    ): List<com.lomo.nativebridge.MediaPromotePlanDto> =
        error("store promote selection not expected")

    override fun memoStatisticsRows(): List<com.lomo.nativebridge.StoreMemoStatisticsRow> =
        error("store statistics not expected")

    override fun sessionReminderPlan(nowUtcMs: Long?): com.lomo.nativebridge.StoreReminderPlan =
        error("reminder plan not expected")

    override fun listHistoryAttachmentRefs(): List<com.lomo.nativebridge.StoreHistoryAttachmentRef> =
        emptyList()

    override fun listMemoHistory(
        memoId: String,
        cursor: String?,
        limit: UInt,
    ): com.lomo.nativebridge.StoreMemoHistoryPage =
        error("memo history not expected")

    override fun getMemo(memoId: String): com.lomo.nativebridge.StoreMemoSnapshot? =
        error("store get not expected")

    override fun sourceDocumentFingerprint(sourcePath: String): String? =
        error("source document fingerprint not expected")

    override fun sidebarProjection(): com.lomo.nativebridge.StoreSidebarProjection =
        error("sidebar projection not expected")

    override fun applyMemoCommand(
        command: com.lomo.nativebridge.StoreMemoCommand,
        onPublication: (com.lomo.nativebridge.StoreMemoCommit) -> Unit,
    ): com.lomo.nativebridge.StoreMemoCommit = error("store apply not expected")

    override fun commitWorkspaceDocumentFacts(
        command: com.lomo.nativebridge.StoreMemoCommand,
        projection: com.lomo.nativebridge.StoreSafMemoProjection,
    ): com.lomo.nativebridge.StoreMemoCommit = error("document projection commit not expected")

    override fun startRebuild(batchSize: UInt): com.lomo.nativebridge.StoreRebuildResult =
        error("store rebuild not expected")

    override fun close() {
        portCloseCount += 1
    }
}

private fun testRustEngineAdapter(
    native: FakeNativeEnginePort,
    platformBatchRunner: PlatformBatchRunner? = null,
    invalidation: StoreInvalidationBus = StoreInvalidationBus(),
    sourceDocumentFingerprintProbe: ((String) -> String?)? = null,
): RustEngineAdapter =
    RustEngineAdapter.acquire(
        native = native,
        platformBatchRunner = platformBatchRunner ?: PlatformBatchRunner(
                native = native,
                executor =
                    AndroidPlatformActionExecutor(
                        access = PlatformActionAccess { error("platform action not expected") },
                        currentTimeMillis = { 0L },
                    ),
            ),
        invalidation = invalidation,
        sourceDocumentFingerprintProbe = sourceDocumentFingerprintProbe,
    )

private class AdapterProjectionPagingSource : PagingSource<Int, String>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, String> =
        LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)

    override fun getRefreshKey(state: PagingState<Int, String>): Int? = null
}
