package com.lomo.data.engine

/*
 * Behavior Contract:
 * - Unit under test: RustEngineAdapter.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: expose Rust engine readiness as a platform-neutral StateFlow while treating every
 *   callback as an invalidation, explicitly closing the native subscription, and closing the
 *   native port/engine exactly once.
 *
 * Scenarios:
 * - Given a native Ready snapshot, when the adapter starts, then readiness contains the Rust-owned
 *   core revision and event sequence.
 * - Given an event claims a revision but the current native snapshot differs, when the callback is
 *   handled, then the adapter publishes the snapshot and never merges callback payload as truth.
 * - Given event sequence N is followed by N+2, when the gap is observed, then the adapter reloads
 *   the complete snapshot rather than manufacturing N+1.
 * - Given foreground resumption or adapter close, when requested, then state is resnapshotted and
 *   the native subscription and port are each closed exactly once.
 * - Given Opening with a platform batch runner, when bootstrap completes, then Ready is published
 *   after the runner drives the job.
 * - Given state read, bootstrap drive, or subscribe fails during acquisition, when construction
 *   aborts, then the native port closes exactly once and no callback listener remains published.
 * - Given the subscription refuses to close, when the adapter closes, then the native port is still
 *   released exactly once and the subscription failure is reported.
 * - Given the state read fails after a Ready snapshot, when an event or resnapshot arrives, then
 *   readiness becomes typed recovery instead of keeping the stale Ready.
 * - Given the engine reports an unknown failure category, when the snapshot decodes, then readiness
 *   fails closed and keeps the unknown value in the diagnostic.
 * - Given a SAF projection scan outlives one driver window, when the same Rust job later completes,
 *   then the rebuild resumes without starting a duplicate scan; a job past its total deadline aborts.
 * - Given two callers receive the same deduplicated job id, when both drive it concurrently, then
 *   only one caller enters the platform driver at a time.
 * - Given two refresh callers rebuild the SAF projection concurrently, when the first is active,
 *   then the second shares its result instead of opening another native rebuild.
 *
 * Observable outcomes:
 * - StateFlow readiness, native state-read count, subscription closure, and port closure.
 *
 * TDD proof:
 * - RED on 2026-07-27: state/bootstrap/subscribe exceptions escape the constructor while the
 *   acquired native port remains open and a failing subscribe can retain its listener.
 * - RED on 2026-07-27: a throwing subscription close skipped `native.close()` entirely, leaking the
 *   engine handle and its workspace lock.
 * - RED on 2026-07-27: a failing state read or an unknown failure category escaped the adapter, so
 *   `readiness` kept the last Ready and the write gate stayed open against an unknown engine.
 * - RED on 2026-08-05: a non-terminal projection scan was aborted after one driver window instead
 *   of resuming the same durable Rust job.
 * - RED on 2026-08-06: two callers entered the platform driver concurrently for one deduplicated
 *   job id, allowing both to submit a result for the same durable batch.
 *
 * Excludes:
 * - SAF action execution internals, workspace selection persistence, Compose rendering, and Rust.
 * - BoltFFI callback-thread enqueue (covered by BoundedInvalidationQueueTest).
 *
 * Test Change Justification:
 * - Reason category: production memo persistence cutover from Room to lomo-store ports.
 * - Old behavior/assertion being replaced: adapter readiness paths that assumed Room-era
 *   dual-authority index or non-store engine packaging.
 * - Why old assertion is no longer correct: the sole production engine surface now includes
 *   store-backed rebuild/query ports behind the same readiness StateFlow contract.
 * - Coverage preserved by: snapshot-over-callback truth, sequence-gap resnapshot, subscription
 *   and port single-close, and Opening→Ready bootstrap remain asserted.
 * - Why this is not fitting the test to the implementation: assertions still lock observable
 *   readiness and close counts, not store schema details.
 */

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
import com.lomo.data.engine.lan.LanServiceState
import com.lomo.data.engine.lan.LanSendItemPlan
import com.lomo.data.engine.lan.LanSessionChallenge
import com.lomo.data.engine.lan.LanSessionState
import com.lomo.data.engine.lan.LanTransferShape
import com.lomo.domain.model.EngineReadiness
import com.lomo.nativebridge.PlatformBatchResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RustEngineAdapterTest : DataFunSpec() {
    init {
        test("given native ready state when adapter starts then Rust revision and sequence are exposed") {
            val native = FakeNativeEnginePort(NativeEngineSnapshot.Ready(coreRevision = 4uL, eventSequence = 9uL))

            val adapter = testRustEngineAdapter(native)

            adapter.readiness.value shouldBe EngineReadiness.Ready(coreRevision = 4uL, eventSequence = 9uL)
            native.stateReads shouldBe 1
            adapter.close()
        }

        test("given callback payload differs from native state when event arrives then complete snapshot wins") {
            val native = FakeNativeEnginePort(NativeEngineSnapshot.Ready(coreRevision = 1uL, eventSequence = 5uL))
            val adapter = testRustEngineAdapter(native)
            native.snapshot = NativeEngineSnapshot.Ready(coreRevision = 2uL, eventSequence = 6uL)

            native.emit(NativeCoreEvent(coreRevision = 999uL, eventSequence = 6uL))

            adapter.readiness.value shouldBe EngineReadiness.Ready(coreRevision = 2uL, eventSequence = 6uL)
            native.stateReads shouldBe 2
            adapter.close()
        }

        test("given sequence gap when N plus 2 arrives then adapter never manufactures the missing state") {
            val native = FakeNativeEnginePort(NativeEngineSnapshot.Ready(coreRevision = 3uL, eventSequence = 10uL))
            val adapter = testRustEngineAdapter(native)
            native.snapshot =
                NativeEngineSnapshot.ReadOnlyRecovery(
                    EngineFailureSnapshot(
                        category = "permission",
                        code = "saf_grant_revoked",
                        retryDisposition = "after_user_action",
                        diagnostic = "Workspace permission is no longer available",
                    ),
                )

            native.emit(NativeCoreEvent(coreRevision = 3uL, eventSequence = 12uL))

            adapter.readiness.value shouldBe
                EngineReadiness.ReadOnlyRecovery(
                    category = EngineReadiness.FailureCategory.PERMISSION,
                    code = "saf_grant_revoked",
                    retryDisposition = EngineReadiness.RetryDisposition.AFTER_USER_ACTION,
                    diagnostic = "Workspace permission is no longer available",
                )
            native.stateReads shouldBe 2
            adapter.close()
        }

        test("given foreground resnapshot and repeated close then state reloads and subscription and port close once") {
            val native = FakeNativeEnginePort(NativeEngineSnapshot.AwaitingWorkspaceSelection)
            val adapter = testRustEngineAdapter(native)
            native.snapshot = NativeEngineSnapshot.Ready(coreRevision = 2uL, eventSequence = 3uL)

            adapter.resnapshot()
            adapter.close()
            adapter.close()

            adapter.readiness.value shouldBe EngineReadiness.Ready(coreRevision = 2uL, eventSequence = 3uL)
            native.stateReads shouldBe 2
            native.subscriptionCloseCount shouldBe 1
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

            val adapter = RustEngineAdapter.acquire(native, platformBatchRunner = runner)

            adapter.readiness.value shouldBe EngineReadiness.Ready(coreRevision = 0uL, eventSequence = 1uL)
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
            native.hasListener shouldBe false
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
            native.hasListener shouldBe false
        }

        test("given subscribe failure during acquisition then native port and listener are released") {
            val native =
                FakeNativeEnginePort(NativeEngineSnapshot.Ready(coreRevision = 1uL, eventSequence = 1uL)).apply {
                    subscribeFailure = IllegalStateException("subscribe failed")
                }

            val error =
                io.kotest.assertions.throwables.shouldThrow<IllegalStateException> {
                    testRustEngineAdapter(native)
                }

            error.message shouldBe "subscribe failed"
            native.portCloseCount shouldBe 1
            native.hasListener shouldBe false
        }

        test("given state read failure after Ready when an event arrives then readiness fails closed") {
            val native = FakeNativeEnginePort(NativeEngineSnapshot.Ready(coreRevision = 1uL, eventSequence = 1uL))
            val adapter = testRustEngineAdapter(native)
            adapter.readiness.value shouldBe EngineReadiness.Ready(coreRevision = 1uL, eventSequence = 1uL)
            native.stateFailure = IllegalStateException("engine handle vanished")

            native.emit(NativeCoreEvent(coreRevision = 1uL, eventSequence = 2uL))

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

        test("given subscription close failure when adapter closes then the native port is still released") {
            val native =
                FakeNativeEnginePort(NativeEngineSnapshot.Ready(coreRevision = 1uL, eventSequence = 1uL)).apply {
                    subscriptionCloseFailure = IllegalStateException("unsubscribe refused")
                }
            val adapter = testRustEngineAdapter(native)

            val error =
                io.kotest.assertions.throwables.shouldThrow<IllegalStateException> {
                    adapter.close()
                }

            error.message shouldBe "unsubscribe refused"
            native.portCloseCount shouldBe 1
        }

        test("given bounded projection pages when SAF rebuild runs then pages are committed and finished") {
            val native = FakeNativeEnginePort(NativeEngineSnapshot.Ready(coreRevision = 1uL, eventSequence = 1uL)).apply {
                projectionPages += WorkspaceProjectionScanPageSnapshot(listOf(projectionReference("first")), "next")
                projectionPages +=
                    WorkspaceProjectionScanPageSnapshot(
                        listOf(projectionReference("second"), projectionReference("third")),
                        null,
                    )
                pollResults["projection-scan"] =
                    ArrayDeque(listOf(NativeJobStep.Completed, NativeJobStep.Completed))
            }
            val adapter = testRustEngineAdapter(native)

            adapter.rebuildSafProjectionFromWorkspaceScan()

            native.projectionEvents shouldBe listOf("begin", "append:1", "append:2", "finish")
            native.projectionScanRequests shouldBe listOf(256u to null, 256u to "next")
            adapter.close()
        }

        test("given a deduplicated job id when two callers drive it then platform execution is single flight") {
            val firstPollEntered = CountDownLatch(1)
            val releaseFirstPoll = CountDownLatch(1)
            val native =
                FakeNativeEnginePort(
                    NativeEngineSnapshot.Ready(coreRevision = 1uL, eventSequence = 1uL),
                ).apply {
                    pollResults["shared-job"] =
                        ArrayDeque(listOf(NativeJobStep.Completed, NativeJobStep.Completed))
                    onPoll = {
                        if (firstPollEntered.count == 1L) {
                            firstPollEntered.countDown()
                            check(releaseFirstPoll.await(5, TimeUnit.SECONDS))
                        }
                    }
                }
            val adapter = testRustEngineAdapter(native)
            val executor = Executors.newFixedThreadPool(2)

            val first = executor.submit<NativeJobStep> { adapter.driveJob("shared-job") }
            check(firstPollEntered.await(5, TimeUnit.SECONDS))
            val second = executor.submit<NativeJobStep> { adapter.driveJob("shared-job") }
            Thread.sleep(100)

            native.polledJobIds.size shouldBe 1
            releaseFirstPoll.countDown()
            first.get(5, TimeUnit.SECONDS) shouldBe NativeJobStep.Completed
            second.get(5, TimeUnit.SECONDS) shouldBe NativeJobStep.Completed
            executor.shutdownNow()
            adapter.close()
        }

        test("given concurrent SAF refreshes when projection rebuild runs then both share one rebuild") {
            val firstPollEntered = CountDownLatch(1)
            val releaseFirstPoll = CountDownLatch(1)
            val native =
                FakeNativeEnginePort(
                    NativeEngineSnapshot.Ready(coreRevision = 1uL, eventSequence = 1uL),
                ).apply {
                    projectionPages += WorkspaceProjectionScanPageSnapshot(emptyList(), null)
                    pollResults["projection-scan"] = ArrayDeque(listOf(NativeJobStep.Completed))
                    onPoll = {
                        if (firstPollEntered.count == 1L) {
                            firstPollEntered.countDown()
                            check(releaseFirstPoll.await(5, TimeUnit.SECONDS))
                        }
                    }
                }
            val adapter = testRustEngineAdapter(native)
            val executor = Executors.newFixedThreadPool(2)

            val first = executor.submit<com.lomo.nativebridge.StoreRebuildResult> {
                adapter.rebuildSafProjectionFromWorkspaceScan()
            }
            check(firstPollEntered.await(5, TimeUnit.SECONDS))
            val second = executor.submit<com.lomo.nativebridge.StoreRebuildResult> {
                adapter.rebuildSafProjectionFromWorkspaceScan()
            }
            Thread.sleep(100)

            native.projectionEvents shouldBe listOf("begin")
            releaseFirstPoll.countDown()
            second.get(5, TimeUnit.SECONDS) shouldBe first.get(5, TimeUnit.SECONDS)
            native.projectionEvents shouldBe listOf("begin", "append:0", "finish")
            executor.shutdownNow()
            adapter.close()
        }

        test("given a projection scan that outlives one driver window when driven again then the same job is resumed") {
            val native = FakeNativeEnginePort(NativeEngineSnapshot.Ready(coreRevision = 1uL, eventSequence = 1uL)).apply {
                projectionPages += WorkspaceProjectionScanPageSnapshot(listOf(projectionReference("resumed")), null)
                pollResults["projection-scan"] = ArrayDeque(listOf(
                    NativeJobStep.RunningNative(taskKind = "workspace-scan", attempt = 1u, dispatchGeneration = 1uL),
                    NativeJobStep.Completed,
                ))
            }
            val clockValues = ArrayDeque(listOf(0L, PlatformBatchRunner.MAX_WAIT_MILLIS, PlatformBatchRunner.MAX_WAIT_MILLIS))
            val runner =
                PlatformBatchRunner(
                    native = native,
                    executor = AndroidPlatformActionExecutor(
                        access = PlatformActionAccess { error("platform action not expected") },
                        currentTimeMillis = { 0L },
                    ),
                    nowMillis = { clockValues.removeFirstOrNull() ?: PlatformBatchRunner.MAX_WAIT_MILLIS },
                    sleepMillis = {},
                )
            val adapter = testRustEngineAdapter(native, runner)

            adapter.rebuildSafProjectionFromWorkspaceScan()

            native.projectionEvents shouldBe listOf("begin", "append:1", "finish")
            native.polledJobIds shouldBe listOf("projection-scan", "projection-scan")
            native.projectionScanRequests shouldBe listOf(256u to null)
            adapter.close()
        }

        test("given a projection scan stays non-terminal past its job deadline then the rebuild is aborted") {
            val native = FakeNativeEnginePort(NativeEngineSnapshot.Ready(coreRevision = 1uL, eventSequence = 1uL)).apply {
                pollResults["projection-scan"] = ArrayDeque(listOf(
                    NativeJobStep.RunningNative(taskKind = "workspace-scan", attempt = 1u, dispatchGeneration = 1uL),
                ))
            }
            val driverClock = ArrayDeque(listOf(0L, PlatformBatchRunner.MAX_WAIT_MILLIS))
            val runner =
                PlatformBatchRunner(
                    native = native,
                    executor = AndroidPlatformActionExecutor(
                        access = PlatformActionAccess { error("platform action not expected") },
                        currentTimeMillis = { 0L },
                    ),
                    nowMillis = { driverClock.removeFirstOrNull() ?: PlatformBatchRunner.MAX_WAIT_MILLIS },
                    sleepMillis = {},
                )
            val projectionClock = ArrayDeque(listOf(0L, WorkspaceNativeAdapter.DEFAULT_JOB_DEADLINE_MILLIS.toLong()))
            val adapter = testRustEngineAdapter(
                native = native,
                platformBatchRunner = runner,
                projectionScanNowMillis = { projectionClock.removeFirstOrNull() ?: Long.MAX_VALUE },
            )

            io.kotest.assertions.throwables.shouldThrow<ProjectionScanDeadlineExceededException> {
                adapter.rebuildSafProjectionFromWorkspaceScan()
            }

            native.projectionEvents shouldBe listOf("begin", "abort")
            adapter.close()
        }

        test("given projection append failure when SAF rebuild runs then the native rebuild is aborted") {
            val native = FakeNativeEnginePort(NativeEngineSnapshot.Ready(coreRevision = 1uL, eventSequence = 1uL)).apply {
                projectionPages += WorkspaceProjectionScanPageSnapshot(emptyList(), null)
                pollResults["projection-scan"] = ArrayDeque(listOf(NativeJobStep.Completed))
                projectionAppendFailure = IllegalStateException("append refused")
            }
            val adapter = testRustEngineAdapter(native)

            io.kotest.assertions.throwables.shouldThrow<IllegalStateException> {
                adapter.rebuildSafProjectionFromWorkspaceScan()
            }.message shouldBe "append refused"
            native.projectionEvents shouldBe listOf("begin", "append:0", "abort")
            adapter.close()
        }

        test("given typed Rust projection failure when SAF rebuild runs then code and category survive") {
            val native = FakeNativeEnginePort(NativeEngineSnapshot.Ready(coreRevision = 1uL, eventSequence = 1uL)).apply {
                projectionPages += WorkspaceProjectionScanPageSnapshot(emptyList(), null)
                pollResults["projection-scan"] = ArrayDeque(
                    listOf(
                        NativeJobStep.Failed(
                            EngineFailureSnapshot(
                                category = "permission",
                                code = "saf_grant_revoked",
                                retryDisposition = "after_user_action",
                                diagnostic = "grant missing",
                            ),
                        ),
                    ),
                )
            }
            val adapter = testRustEngineAdapter(native)

            val failure = io.kotest.assertions.throwables.shouldThrow<ProjectionRebuildException> {
                adapter.rebuildSafProjectionFromWorkspaceScan()
            }
            failure.failureCode shouldBe "saf_grant_revoked"
            failure.failureCategory shouldBe "permission"
            native.projectionEvents shouldBe listOf("begin", "abort")
            adapter.close()
        }
    }
}

private fun projectionReference(id: String): SafMemoProjectionReferenceSnapshot =
    SafMemoProjectionReferenceSnapshot(
        memoId = id,
        sourcePath = "$id.md",
        fileFingerprint = "a".repeat(64),
        chronologyEpochMs = 1L,
        content =
            ExchangeArtifactReference(
                token = "ex.${"b".repeat(64)}.body",
                length = 1uL,
                digest = "b".repeat(64),
            ),
        tags = emptyList(),
        attachmentPaths = emptyList(),
        hasTodo = false,
        hasUrl = false,
        reminders = emptyList(),
    )

private class FakeNativeEnginePort(
    initialSnapshot: NativeEngineSnapshot,
) : WorkspaceNativeEnginePort {
    val projectionPages = ArrayDeque<WorkspaceProjectionScanPageSnapshot>()
    val projectionEvents = mutableListOf<String>()
    val projectionScanRequests = mutableListOf<Pair<UInt, String?>>()
    val polledJobIds = mutableListOf<String>()
    var projectionAppendFailure: Throwable? = null
    override fun updateLanNetworkSnapshot(snapshot: LanNetworkFacts) = error("LAN not expected")

    override fun updateLanDiscoverySnapshot(snapshot: LanDiscoveryFacts) = error("LAN not expected")

    override fun startLanService(): LanServiceState = error("LAN not expected")

    override fun stopLanService(): LanServiceState = error("LAN not expected")

    override fun listLanDiscoveredPeers(): List<LanDiscoveredPeer> = error("LAN not expected")

    override fun lanTransferShape(): LanTransferShape = error("LAN not expected")

    override fun configureLanIdentity(identity: LanDeviceIdentity): LanLocalIdentity =
        error("LAN not expected")

    override fun beginLanPairing(
        peerDeviceId: String,
        nowMs: Long,
        ttlMs: Long,
    ): LanPairingChallenge = error("LAN not expected")

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
    ): String = error("LAN not expected")

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
        )

    var snapshot: NativeEngineSnapshot = initialSnapshot
    var stateReads: Int = 0
    var subscriptionCloseCount: Int = 0
    var portCloseCount: Int = 0
    val pollResults = mutableMapOf<String, ArrayDeque<NativeJobStep>>()
    var afterSubmitSnapshot: NativeEngineSnapshot? = null
    var onPoll: (() -> Unit)? = null
    var stateFailure: Throwable? = null
    var subscribeFailure: Throwable? = null
    var subscriptionCloseFailure: Throwable? = null
    private var listener: ((NativeCoreEvent) -> Unit)? = null
    val hasListener: Boolean
        get() = listener != null

    override fun state(): NativeEngineSnapshot {
        stateReads += 1
        stateFailure?.let { throw it }
        return snapshot
    }

    override fun subscribe(listener: (NativeCoreEvent) -> Unit): NativeEngineSubscription {
        this.listener = listener
        subscribeFailure?.let { throw it }
        return NativeEngineSubscription {
            subscriptionCloseCount += 1
            this.listener = null
            subscriptionCloseFailure?.let { throw it }
        }
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

    override fun beginSafProjectionRebuild(): String {
        projectionEvents += "begin"
        return "projection-rebuild"
    }

    override fun appendSafProjectionRebuildPage(
        rebuildId: String,
        memos: List<SafMemoProjectionReferenceSnapshot>,
    ) {
        projectionEvents += "append:${memos.size}"
        projectionAppendFailure?.let { throw it }
    }

    override fun finishSafProjectionRebuild(rebuildId: String): com.lomo.nativebridge.StoreRebuildResult {
        projectionEvents += "finish"
        return com.lomo.nativebridge.StoreRebuildResult(
            memosIndexed = 0uL,
            fileCount = 0uL,
            attachmentCount = 0uL,
            workspaceDigest = "a".repeat(64),
            storeDigest = "a".repeat(64),
            corruptLomoIsolated = 0uL,
            highWaterRevision = 1uL,
        )
    }

    override fun abortSafProjectionRebuild(rebuildId: String) {
        projectionEvents += "abort"
    }

    override fun startWorkspaceDocumentCommand(
        path: String,
        expectedState: WorkspaceNativeExpectedState,
        command: WorkspaceNativeCommandSpec,
        deadlineMillis: ULong,
    ): String = error("document command not expected")

    override fun readWorkspaceDocumentCommandResult(jobId: String): WorkspaceNativeCommandResultSnapshot =
        error("document result not expected")

    override fun queryMemos(
        query: com.lomo.nativebridge.StoreMemoQuery,
        cursor: com.lomo.nativebridge.StorePageCursor?,
        pageSize: UInt,
    ): com.lomo.nativebridge.StoreMemoPage = error("store query not expected")

    override fun listHistoryAttachmentRefs(): List<com.lomo.nativebridge.StoreHistoryAttachmentRef> =
        emptyList()

    override fun getMemo(memoId: String): com.lomo.nativebridge.StoreMemoSnapshot? =
        error("store get not expected")

    override fun sidebarProjection(): com.lomo.nativebridge.StoreSidebarProjection =
        error("sidebar projection not expected")

    override fun applyMemoCommand(
        command: com.lomo.nativebridge.StoreMemoCommand,
    ): com.lomo.nativebridge.StoreMemoCommit = error("store apply not expected")

    override fun commitSafProjectionMutation(
        command: com.lomo.nativebridge.StoreMemoCommand,
        projection: com.lomo.nativebridge.StoreSafMemoProjection?,
    ): com.lomo.nativebridge.StoreMemoCommit = error("SAF projection commit not expected")

    override fun startRebuild(batchSize: UInt): com.lomo.nativebridge.StoreRebuildResult =
        error("store rebuild not expected")

    override fun close() {
        portCloseCount += 1
        listener = null
    }

    fun emit(event: NativeCoreEvent) {
        listener?.invoke(event)
    }
}

private fun testRustEngineAdapter(
    native: FakeNativeEnginePort,
    platformBatchRunner: PlatformBatchRunner? = null,
    projectionScanNowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
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
        projectionScanNowMillis = projectionScanNowMillis,
    )
