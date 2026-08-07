package com.lomo.data.engine

import com.lomo.domain.model.ProjectionFreshness

/*
 * Behavior Contract:
 * - Unit under test: ManagedEngineSession.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: cold-start AwaitingWorkspaceSelection; activate installs only Ready candidates and
 *   only closes the previous engine after Ready install; soft Recovery / hard open failure keep
 *   previous authority; cold-restore hard failure freezes Recovery authority against bootstrap
 *   resnapshot; clearWorkspace serializes under the activation mutex.
 *
 * Scenarios:
 * - Given no configured root, when the session starts, then readiness is AwaitingWorkspaceSelection.
 * - Given bootstrap engine acquisition fails, when the session is constructed, then the graph
 *   remains available in structured ReadOnlyRecovery without a placeholder adapter.
 * - Given native acquisition returns a typed EngineError, when the session enters recovery, then
 *   its category, code, retry disposition and diagnostic reach the UI boundary unchanged.
 * - Given SQLite integrity recovery on cold restore, when the user requests derived-index rebuild,
 *   then the recovery candidate rebuilds only the Rust projection and the same workspace reopens
 *   Ready without a Kotlin database fallback.
 * - Given a committed SAF root with a durable projection, when cold restore refresh is still in
 *   progress, then the existing projection becomes Ready before refresh completes.
 * - Given a committed SAF root with a durable projection, when background refresh exceeds its
 *   platform deadline, then the published workspace stays Ready instead of entering Recovery.
 * - Given a candidate loses Ready while its durable projection revision is inspected, when cold
 *   restore reaches the commit boundary, then the candidate is rejected with its structured Rust
 *   recovery and no workspace authority is published.
 * - Given Direct selection, when activate succeeds with Ready, then Ready is published and previous
 *   adapter closes once.
 * - Given open throws, when activate fails, then previous readiness remains and candidate is not
 *   installed.
 * - Given repeated SAF selection, when activation rotates the capability token, then the stable
 *   workspace ID supplied to native remains unchanged while activation and projection revisions
 *   advance together.
 * - Given SAF scan projection rebuild fails, when activation runs, then the candidate is rejected
 *   and the previous Ready workspace remains authoritative.
 * - Given candidate opens as ReadOnlyRecovery, when activate runs, then previous engine stays and
 *   the soft failure is thrown without installing Recovery as success.
 * - Given a soft-failed candidate whose close also throws, when activate runs, then the structured
 *   activation failure still surfaces and the candidate capability is revoked.
 * - Given the previous engine refuses to close, when a Ready candidate is promoted, then neither
 *   engine stays published, both capabilities are revoked and readiness freezes in Recovery.
 * - Given the active engine refuses to close, when the session closes, then the capability is still
 *   revoked and terminal ShuttingDown readiness is still published.
 * - Given persisted root that hard-fails open, when cold restore fails, then Recovery holds and
 *   bootstrap resnapshot cannot overwrite it with Awaiting.
 * - Given an active adapter publishes Recovery at a boundary, when the session mirrors it, then the
 *   old workspace authority is cleared before any query can reuse it.
 * - Given an active Ready adapter, when workspace scan start/drive/read routes through the session,
 *   then all calls use that adapter's same native port and no additional engine is opened.
 * - Given an in-flight workspace call, when a Ready candidate is installed, then the session's
 *   exclusive lease waits for the call to release before closing the previous port.
 * - Given the domain render boundary, when it renders through the session, then the same active
 *   adapter and native port serve the request without constructing another engine.
 * - Given a Rust-scanned reminder reference, when queried and rewritten, then typed facts are
 *   mapped without raw parsing and the complete reference is sent through the same session port.
 * - Given no workspace, when a trusted LAN session begins, then it uses the bootstrap engine
 *   handle and remains independent of workspace readiness.
 * - Given a Ready workspace, when an authenticated LAN batch is prepared and queried, then the
 *   active engine handle owns the batch runtime rather than a free-function side channel.
 * - Given an approved LAN batch, when chunk send/resume is routed, then coordinates and bytes use
 *   that same managed handle and no Kotlin wire owner is constructed.
 * - Given Rust reports a durable received batch outcome, when the runtime inbox is queried, then
 *   the same managed handle exposes its decision and typed per-item recovery result.
 *
 * Observable outcomes: readiness StateFlow, open request workspace shape, adapter close counts,
 * workspace port identity and engine-open count.
 * TDD proof: RED on 2026-07-27 because NativeWorkspaceSelection.Saf exposed no stableWorkspaceId;
 * repeated activation could only send the newly randomized capability token to native.
 * TDD proof: RED on 2026-07-27 because a throwing engine close skipped capability revoke, terminal
 * readiness and candidate release, and a failed previous retirement still published the candidate.
 * TDD proof: RED on 2026-08-02 because a SAF candidate reached Ready without publishing a queryable
 * store projection, so projection failure was never observed before authority changed.
 * TDD proof: A-AUTH-001 RED because WorkspaceAuthority did not carry the store projection's
 * high-water revision, so consumers could not bind Paging to the promoted projection generation.
 * TDD proof: RED on 2026-08-05 because adapter Recovery left the session's previous authority
 * published after an invalidated engine boundary.
 * TDD proof: RED on 2026-08-06 because cold SAF restore synchronously rebuilt the disposable
 * projection before promotion, so a slow or timed-out refresh blocked Ready and became read-only.
 * TDD proof: RED on 2026-08-06 because candidate readiness was checked only before projection
 * inspection; a Rust recovery published during that inspection was still committed as authority.
 * TDD proof: RED on 2026-08-06 because direct BoltFFI EngineError failures were collapsed into the
 * generic workspace_open_failed code at the session boundary.
 * Excludes: live BoltFFI LomoEngine.open (device/native-smoke) and Compose recovery UI.
 *
 * Test Change Justification:
 * - Reason category: production memo persistence cutover from Room to lomo-store ports.
 * - Old behavior/assertion being replaced: session tests that assumed Room-backed workspace
 *   projection helpers or dual-authority index rebuild semantics.
 * - Why old assertion is no longer correct: production now installs a single native store-backed
 *   engine port; Room projection/index tails are deleted.
 * - Coverage preserved by: readiness publish, activate/close ordering, leased workspace route
 *   identity, and recovery freeze scenarios remain asserted.
 * - Why this is not fitting the test to the implementation: outcomes stay product-visible
 *   readiness and port-lease behavior, not private store SQL.
 * - SAF fixture correction: the prior `content://tree/...` string omitted the required
 *   `/tree/<document-id>` path and never represented a valid DocumentsContract tree URI.
 */

import com.lomo.data.testing.DataFunSpec
import com.lomo.data.engine.lan.LanBindCandidate
import com.lomo.data.engine.lan.LanBatchPreview
import com.lomo.data.engine.lan.LanBatchRecovery
import com.lomo.data.engine.lan.LanDeviceIdentity
import com.lomo.data.engine.lan.LanDiscoveredPeer
import com.lomo.data.engine.lan.LanDiscoveryFacts
import com.lomo.data.engine.lan.LanLocalIdentity
import com.lomo.data.engine.lan.LanNetworkFacts
import com.lomo.data.engine.lan.LanPairingChallenge
import com.lomo.data.engine.lan.LanPendingBatch
import com.lomo.data.engine.lan.LanPeerPage
import com.lomo.data.engine.lan.LanReceivedBatchDecision
import com.lomo.data.engine.lan.LanReceivedItemRecovery
import com.lomo.data.engine.lan.LanRuntimeInbox
import com.lomo.data.engine.lan.LanServicePhase
import com.lomo.data.engine.lan.LanServiceState
import com.lomo.data.engine.lan.LanSendItemPlan
import com.lomo.data.engine.lan.LanSessionChallenge
import com.lomo.data.engine.lan.LanSessionPhase
import com.lomo.data.engine.lan.LanSessionState
import com.lomo.data.engine.lan.LanTransferShape
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.domain.model.StorageTimestampFormats
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.MarkdownWorkspaceRepository
import com.lomo.domain.model.MarkdownWorkspaceCommandException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.time.LocalDateTime
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class ManagedEngineSessionTest : DataFunSpec() {
    init {
        test("given the domain render boundary when rendering then it uses the active session port") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-render-boundary").toFile()
                try {
                    val port =
                        SessionFakeNativeEnginePort(
                            NativeEngineSnapshot.Ready(coreRevision = 1uL, eventSequence = 1uL),
                        )
                    val session =
                        ManagedEngineSession(
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { testRustEngineAdapter(port) },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { false },
                        )

                    val repository: MarkdownWorkspaceRepository = session
                    repository.renderMarkdown("hello").plainText shouldBe "rendered:hello"
                    port.renderCallCount shouldBe 1
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                }
            }
        }

        test("given a typed task span when toggled then the session translates it against the scanned body") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-task").toFile()
                try {
                    val port = readyTaskPort()
                    val session = readySession(filesDir, port, testScheduler)

                    val updated =
                        session.toggleTask(
                            memoIdentity = "2026-07-20_10:00:00_0",
                            actionSpan = MarkdownSourceSpan(startByte = 2uL, endByte = 5uL),
                        )

                    updated shouldBe "- [x] task"
                    port.lastDocumentCommand shouldBe
                        WorkspaceNativeCommandSpec.ToggleTask(
                            sourceStart = 13uL,
                            sourceEnd = 16uL,
                        )
                    port.lastExpectedFingerprint shouldBe "a".repeat(64)
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                }
            }
        }

        test("given a stale document command when toggled then the structured failure is observable") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-task-stale").toFile()
                try {
                    val port = readyTaskPort()
                    port.documentTerminal =
                        NativeJobStep.Failed(
                            EngineFailureSnapshot(
                                category = "VALIDATION",
                                code = "stale_snapshot",
                                retryDisposition = "NEVER",
                                diagnostic = "document changed",
                            ),
                        )
                    val session = readySession(filesDir, port, testScheduler)

                    val error =
                        shouldThrow<MarkdownWorkspaceCommandException> {
                            session.toggleTask(
                                memoIdentity = "2026-07-20_10:00:00_0",
                                actionSpan = MarkdownSourceSpan(startByte = 2uL, endByte = 5uL),
                            )
                        }

                    error.code shouldBe "stale_snapshot"
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                }
            }
        }

        test("given a scanned reminder when queried and rewritten then the exact typed reference crosses the session") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-reminder").toFile()
                try {
                    val port = readyReminderPort()
                    val session = readySession(filesDir, port, testScheduler)

                    val reminder = session.remindersForMemo("2026-07-20_10:00:00_0").single()
                    val updated = session.rewriteReminder(reminder.reference, "@2026-07-20-10:45x2.1")

                    reminder.dueAt shouldBe LocalDateTime.of(2026, 7, 20, 9, 30)
                    reminder.repeatCount shouldBe 2
                    reminder.reference.opaqueId shouldBe "reminder-id"
                    updated shouldBe "done"
                    port.lastExpectedFingerprint shouldBe "a".repeat(64)
                    port.lastDocumentCommand shouldBe
                        WorkspaceNativeCommandSpec.RewriteReminder(
                            reminder = reminderSnapshot(),
                            replacement = "@2026-07-20-10:45x2.1",
                        )
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                }
            }
        }

        test("given no root when session starts then readiness is AwaitingWorkspaceSelection") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine").toFile()
                try {
                    val opens = mutableListOf<NativeEngineOpenRequest>()
                    val session =
                        ManagedEngineSession(
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { request ->
                                opens += request
                                testRustEngineAdapter(
                                    SessionFakeNativeEnginePort(NativeEngineSnapshot.AwaitingWorkspaceSelection),
                                )
                            },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { false },
                        )

                    session.readiness.value shouldBe EngineReadiness.AwaitingWorkspaceSelection
                    opens.single().workspace shouldBe null
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                }
            }
        }

        test("given no workspace when LAN starts then the bootstrap engine owns the listener") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-lan-bootstrap").toFile()
                try {
                    val port =
                        SessionFakeNativeEnginePort(NativeEngineSnapshot.AwaitingWorkspaceSelection)
                    val session =
                        ManagedEngineSession(
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { testRustEngineAdapter(port) },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { false },
                        )

                    session.updateLanNetworkSnapshot(
                        LanNetworkFacts(
                            revision = 1uL,
                            localNetworkPermissionGranted = true,
                            candidates = listOf(LanBindCandidate(host = "127.0.0.1", port = 0u)),
                        ),
                    )
                    session.startLanService() shouldBe
                        LanServiceState(
                            phase = LanServicePhase.Listening,
                            listenAddress = "127.0.0.1:43123",
                        )
                    port.lanStartCount shouldBe 1
                    session.readiness.value shouldBe EngineReadiness.AwaitingWorkspaceSelection
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                }
            }
        }

        test("given no workspace when a LAN session begins then the bootstrap engine owns it") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-lan-session").toFile()
                try {
                    val port =
                        SessionFakeNativeEnginePort(NativeEngineSnapshot.AwaitingWorkspaceSelection)
                    val session =
                        ManagedEngineSession(
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { testRustEngineAdapter(port) },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { false },
                        )

                    val challenge = session.beginLanSession("a".repeat(64), 1_000, 60_000)

                    challenge shouldBe port.sessionChallenge
                    port.lanSessionBegins shouldBe 1
                    session.lanRuntimeInbox() shouldBe port.runtimeInbox
                    session.lanRuntimeInbox().batchRecoveries shouldBe
                        port.runtimeInbox.batchRecoveries
                    session.pollLanListener(1_100) shouldBe port.runtimeInbox
                    session.lanSessionState(challenge.sessionId) shouldBe
                        LanSessionState(
                            sessionId = challenge.sessionId,
                            peerDeviceId = challenge.peerDeviceId,
                            phase = LanSessionPhase.Authenticated,
                        )
                    session.readiness.value shouldBe EngineReadiness.AwaitingWorkspaceSelection
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                }
            }
        }

        test("given Ready workspace when a LAN batch is prepared then the active engine owns it") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-lan-batch").toFile()
                try {
                    val port =
                        SessionFakeNativeEnginePort(
                            NativeEngineSnapshot.Ready(coreRevision = 8uL, eventSequence = 13uL),
                        )
                    val session =
                        ManagedEngineSession(
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { testRustEngineAdapter(port) },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { false },
                        )
                    val item =
                        LanSendItemPlan(
                            timestampMs = 1_700_000_000_000,
                            contentDigest = "0".repeat(64),
                            contentBytes = 4uL,
                            title = "Preview",
                            attachments = emptyList(),
                        )

                    session.prepareLanBatch("b".repeat(32), "batch-managed", listOf(item))

                    port.preparedLanBatchId shouldBe "batch-managed"
                    session.lanBatchPreview("batch-managed") shouldBe port.batchPreview
                    session.lanUnconfirmedBatchChunks("batch-managed", 0u, 65_535u) shouldBe
                        listOf(0u)
                    session.sendLanBatchChunk(
                        "b".repeat(32),
                        "batch-managed",
                        0u,
                        65_535u,
                        0u,
                        byteArrayOf(1, 2, 3, 4),
                    )
                    port.sentLanChunk shouldBe byteArrayOf(1, 2, 3, 4)
                    session.commitReceivedLanItem("batch-managed", 0u, 1_500) shouldBe "memo-received"
                    port.committedLanBatchId shouldBe "batch-managed"
                    port.committedLanItemIndex shouldBe 0u
                    session.rejectLanBatch("b".repeat(32), "batch-managed", 2_000)
                    port.rejectedLanBatchId shouldBe "batch-managed"
                    session.readiness.value shouldBe
                        EngineReadiness.Ready(coreRevision = 8uL, eventSequence = 13uL)
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                }
            }
        }

        test("given bootstrap acquisition failure when session starts then Recovery remains available") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-bootstrap-fail").toFile()
                try {
                    val session =
                        ManagedEngineSession(
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { error("native library unavailable") },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { false },
                        )

                    val recovery =
                        session.readiness.value.shouldBeInstanceOf<EngineReadiness.ReadOnlyRecovery>()
                    recovery.code shouldBe "workspace_open_failed"
                    recovery.diagnostic shouldContain "native library unavailable"
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                }
            }
        }

        test("given typed native acquisition failure when session starts then structured recovery is preserved") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-typed-open-fail").toFile()
                try {
                    val nativeFailure =
                        com.lomo.nativebridge.EngineError.Failure(
                            com.lomo.nativebridge.EngineFailure(
                                category = "permission",
                                code = "saf_grant_revoked",
                                retryDisposition = "after_user_action",
                                operationId = null,
                                jobId = null,
                                diagnostic = "Persisted tree grant is no longer writable",
                            ),
                        )
                    val session =
                        ManagedEngineSession(
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { throw nativeFailure },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { false },
                        )

                    val recovery =
                        session.readiness.value.shouldBeInstanceOf<EngineReadiness.ReadOnlyRecovery>()
                    recovery.category shouldBe EngineReadiness.FailureCategory.PERMISSION
                    recovery.code shouldBe "saf_grant_revoked"
                    recovery.retryDisposition shouldBe EngineReadiness.RetryDisposition.AFTER_USER_ACTION
                    recovery.diagnostic shouldBe "Persisted tree grant is no longer writable"
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                }
            }
        }

        test("given SQLite recovery when derived index is rebuilt then the same workspace reopens Ready") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-recovery-rebuild").toFile()
                val workspace = kotlin.io.path.createTempDirectory("ws-recovery-rebuild").toFile()
                try {
                    val settings = InMemoryDirectorySettingsRepository()
                    settings.setLocation(StorageArea.ROOT, StorageLocation(workspace.absolutePath))
                    var rebuilt = false
                    val workspacePorts = mutableListOf<SessionFakeNativeEnginePort>()
                    val session =
                        ManagedEngineSession(
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { request ->
                                val port =
                                    if (request.workspace == null) {
                                        SessionFakeNativeEnginePort(
                                            NativeEngineSnapshot.AwaitingWorkspaceSelection,
                                        )
                                    } else if (rebuilt) {
                                        SessionFakeNativeEnginePort(
                                            NativeEngineSnapshot.Ready(
                                                coreRevision = 3uL,
                                                eventSequence = 5uL,
                                            ),
                                        )
                                    } else {
                                        SessionFakeNativeEnginePort(
                                            NativeEngineSnapshot.ReadOnlyRecovery(
                                                EngineFailureSnapshot(
                                                    category = "corruption",
                                                    code = "sqlite_integrity_failed",
                                                    retryDisposition = "after_user_action",
                                                    diagnostic = "PRAGMA quick_check did not return ok",
                                                ),
                                            ),
                                        ).also { recoveryPort ->
                                            recoveryPort.onRebuild = { rebuilt = true }
                                        }
                                    }
                                if (request.workspace != null) workspacePorts += port
                                testRustEngineAdapter(port)
                            },
                            directorySettingsRepository = settings,
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { false },
                        )
                    advanceUntilIdle()
                    session.readiness.value
                        .shouldBeInstanceOf<EngineReadiness.ReadOnlyRecovery>()
                        .code shouldBe "sqlite_integrity_failed"

                    val result = session.rebuildDerivedIndex()

                    result.memosIndexed shouldBe 2uL
                    workspacePorts.sumOf { it.rebuildCount } shouldBe 1
                    session.readiness.value shouldBe
                        EngineReadiness.Ready(coreRevision = 3uL, eventSequence = 5uL)
                    session.activeWorkspaceLocation.value shouldBe StorageLocation(workspace.absolutePath)
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                    workspace.deleteRecursively()
                }
            }
        }

        test("given direct root when activate succeeds then Ready is published and previous closes") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-ready").toFile()
                val workspace = kotlin.io.path.createTempDirectory("ws-direct").toFile()
                try {
                    val closedPorts = mutableListOf<SessionFakeNativeEnginePort>()
                    val session =
                        ManagedEngineSession(
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { request ->
                                val snapshot =
                                    if (request.workspace == null) {
                                        NativeEngineSnapshot.AwaitingWorkspaceSelection
                                    } else {
                                        NativeEngineSnapshot.Ready(coreRevision = 0uL, eventSequence = 3uL)
                                    }
                                val port = SessionFakeNativeEnginePort(snapshot)
                                closedPorts += port
                                testRustEngineAdapter(port)
                            },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { false },
                        )

                    session.activateWorkspace(StorageLocation(workspace.absolutePath))

                    session.readiness.value shouldBe
                        EngineReadiness.Ready(coreRevision = 0uL, eventSequence = 3uL)
                    // Bootstrap adapter closed after activate installed the candidate.
                    closedPorts.first().portCloseCount shouldBe 1
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                    workspace.deleteRecursively()
                }
            }
        }

        test("given an active adapter boundary recovery when resnapshot runs then workspace authority is cleared") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-boundary-recovery").toFile()
                val workspace = kotlin.io.path.createTempDirectory("ws-boundary-recovery").toFile()
                try {
                    val ports = mutableListOf<SessionFakeNativeEnginePort>()
                    val session =
                        ManagedEngineSession(
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { request ->
                                val port =
                                    SessionFakeNativeEnginePort(
                                        if (request.workspace == null) {
                                            NativeEngineSnapshot.AwaitingWorkspaceSelection
                                        } else {
                                            NativeEngineSnapshot.Ready(coreRevision = 1uL, eventSequence = 1uL)
                                        },
                                    )
                                ports += port
                                testRustEngineAdapter(port)
                            },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { false },
                        )
                    session.activateWorkspace(StorageLocation(workspace.absolutePath))
                    checkNotNull(session.workspaceAuthority.value)

                    ports.last().snapshot = NativeEngineSnapshot.ReadOnlyRecovery(
                        EngineFailureSnapshot(
                            category = "internal",
                            code = "engine_state_unavailable",
                            retryDisposition = "after_user_action",
                            diagnostic = "state read failed",
                        ),
                    )
                    session.resnapshot()

                    session.readiness.value.shouldBeInstanceOf<EngineReadiness.ReadOnlyRecovery>()
                    session.workspaceAuthority.value shouldBe null
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                    workspace.deleteRecursively()
                }
            }
        }

        test("given open throws when activate fails then previous readiness remains") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-fail").toFile()
                try {
                    var openCount = 0
                    val session =
                        ManagedEngineSession(
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { request ->
                                openCount += 1
                                if (request.workspace != null) {
                                    error("native open refused")
                                }
                                testRustEngineAdapter(
                                    SessionFakeNativeEnginePort(NativeEngineSnapshot.AwaitingWorkspaceSelection),
                                )
                            },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { false },
                        )

                    val error =
                        shouldThrow<IllegalStateException> {
                            session.activateWorkspace(StorageLocation("/tmp/candidate-root"))
                        }
                    error.message shouldBe "native open refused"
                    session.readiness.value shouldBe EngineReadiness.AwaitingWorkspaceSelection
                    openCount shouldBe 2 // bootstrap + failed candidate
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                }
            }
        }

        test("given content uri when activate runs then SAF token is registered") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-saf").toFile()
                try {
                    val registry = CapabilityRegistry()
                    var observed: NativeWorkspaceSelection? = null
                    val session =
                        ManagedEngineSession(
                            filesDir = filesDir,
                            capabilityRegistry = registry,
                            openAdapter = { request ->
                                observed = request.workspace
                                val snapshot =
                                    if (request.workspace == null) {
                                        NativeEngineSnapshot.AwaitingWorkspaceSelection
                                    } else {
                                        NativeEngineSnapshot.Ready(coreRevision = 1uL, eventSequence = 1uL)
                                    }
                                testRustEngineAdapter(SessionFakeNativeEnginePort(snapshot))
                            },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { it.startsWith("content://") },
                        )

                    val treeUri = "content://com.lomo.documents/tree/primary%3ALomo"
                    session.activateWorkspace(StorageLocation(treeUri))

                    val saf = observed.shouldBeInstanceOf<NativeWorkspaceSelection.Saf>()
                    registry.resolve(saf.capabilityToken) shouldBe treeUri
                    saf.stableWorkspaceId shouldBe SafWorkspaceIdentity.fromTreeUri(treeUri)
                    session.readiness.value shouldBe
                        EngineReadiness.Ready(coreRevision = 1uL, eventSequence = 1uL)
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                }
            }
        }

        test("given the same SAF tree when activation rotates tokens then native identity is stable") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-saf-identity").toFile()
                try {
                    val observed = mutableListOf<NativeWorkspaceSelection.Saf>()
                    var projectionRevision = 0uL
                    val session =
                        ManagedEngineSession(
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { request ->
                                request.workspace
                                    ?.shouldBeInstanceOf<NativeWorkspaceSelection.Saf>()
                                    ?.let(observed::add)
                                val snapshot =
                                    if (request.workspace == null) {
                                        NativeEngineSnapshot.AwaitingWorkspaceSelection
                                    } else {
                                        NativeEngineSnapshot.Ready(coreRevision = 1uL, eventSequence = 1uL)
                                    }
                                SessionFakeNativeEnginePort(snapshot).apply {
                                    this.projectionHighWaterRevision = projectionRevision
                                    onSafProjectionRebuild = { projectionRevision += 1uL }
                                }.let(::testRustEngineAdapter)
                            },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { it.startsWith("content://") },
                        )

                    val tree =
                        StorageLocation("content://com.lomo.documents/tree/primary%3ALomo")
                    session.activateWorkspace(tree)
                    val firstAuthority = checkNotNull(session.workspaceAuthority.value)
                    session.activateWorkspace(tree)
                    val secondAuthority = checkNotNull(session.workspaceAuthority.value)

                    observed.size shouldBe 2
                    observed[0].stableWorkspaceId shouldBe observed[1].stableWorkspaceId
                    (observed[0].capabilityToken == observed[1].capabilityToken) shouldBe false
                    firstAuthority.workspaceId shouldBe secondAuthority.workspaceId
                    firstAuthority.generation shouldBe 1
                    secondAuthority.generation shouldBe 2
                    firstAuthority.projectionRevision shouldBe 1uL
                    secondAuthority.projectionRevision shouldBe 2uL
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                }
            }
        }

        test("given SAF projection failure when activation runs then previous Ready authority remains") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-saf-projection-fail").toFile()
                val previousRoot = kotlin.io.path.createTempDirectory("ws-saf-projection-previous").toFile()
                try {
                    val registry = CapabilityRegistry()
                    val ports = mutableListOf<SessionFakeNativeEnginePort>()
                    var safToken: String? = null
                    val session =
                        ManagedEngineSession(
                            filesDir = filesDir,
                            capabilityRegistry = registry,
                            openAdapter = { request ->
                                val port =
                                    when (request.workspace) {
                                        null ->
                                            SessionFakeNativeEnginePort(
                                                NativeEngineSnapshot.AwaitingWorkspaceSelection,
                                            )
                                        is NativeWorkspaceSelection.Direct ->
                                            SessionFakeNativeEnginePort(
                                                NativeEngineSnapshot.Ready(coreRevision = 7uL, eventSequence = 9uL),
                                            )
                                        is NativeWorkspaceSelection.Saf -> {
                                            safToken = request.workspace.capabilityToken
                                            SessionFakeNativeEnginePort(
                                                NativeEngineSnapshot.Ready(coreRevision = 11uL, eventSequence = 13uL),
                                            ).apply {
                                                safProjectionFailure = IllegalStateException("SAF projection refused")
                                            }
                                        }
                                    }
                                ports += port
                                testRustEngineAdapter(port)
                            },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { it.startsWith("content://") },
                        )
                    session.activateWorkspace(StorageLocation(previousRoot.absolutePath))
                    val previousAuthority = checkNotNull(session.workspaceAuthority.value)

                    val error =
                        shouldThrow<IllegalStateException> {
                            session.activateWorkspace(
                                StorageLocation("content://com.lomo.documents/tree/primary%3ALomo"),
                            )
                        }

                    error.message shouldBe "SAF projection refused"
                    session.readiness.value shouldBe
                        EngineReadiness.Ready(coreRevision = 7uL, eventSequence = 9uL)
                    session.workspaceAuthority.value shouldBe previousAuthority
                    ports.last().portCloseCount shouldBe 1
                    ports.last().safProjectionRebuildCount shouldBe 1
                    shouldThrow<CapabilityRegistryException> { registry.resolve(checkNotNull(safToken)) }
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                    previousRoot.deleteRecursively()
                }
            }
        }

        test("given pending root transition when session starts then cold restore activates committed root") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-restore").toFile()
                val workspace = kotlin.io.path.createTempDirectory("ws-restore").toFile()
                val candidate = kotlin.io.path.createTempDirectory("ws-pending").toFile()
                try {
                    val settings = InMemoryDirectorySettingsRepository()
                    settings.setLocation(StorageArea.ROOT, StorageLocation(workspace.absolutePath))
                    settings.prepareRootTransition(StorageLocation(candidate.absolutePath))
                    val session =
                        ManagedEngineSession(
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { request ->
                                val snapshot =
                                    if (request.workspace == null) {
                                        NativeEngineSnapshot.AwaitingWorkspaceSelection
                                    } else {
                                        NativeEngineSnapshot.Ready(coreRevision = 2uL, eventSequence = 4uL)
                                    }
                                testRustEngineAdapter(SessionFakeNativeEnginePort(snapshot))
                            },
                            directorySettingsRepository = settings,
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { false },
                        )

                    // Unconfined dispatcher runs cold-restore launch immediately.
                    session.readiness.value shouldBe
                        EngineReadiness.Ready(coreRevision = 2uL, eventSequence = 4uL)
                    session.activeWorkspaceLocation.value shouldBe StorageLocation(workspace.absolutePath)
                    settings.pendingRootTransition() shouldBe null
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                    workspace.deleteRecursively()
                    candidate.deleteRecursively()
                }
            }
        }

        test("given durable SAF projection when cold refresh is in progress then Ready is published first") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-saf-cold-refresh").toFile()
                val tree = StorageLocation("content://com.lomo.documents/tree/primary%3ALomo")
                val refreshEntered = CountDownLatch(1)
                val refreshRelease = CountDownLatch(1)
                val settings = InMemoryDirectorySettingsRepository()
                settings.setLocation(StorageArea.ROOT, tree)
                val candidate =
                    SessionFakeNativeEnginePort(
                        NativeEngineSnapshot.Ready(coreRevision = 5uL, eventSequence = 8uL),
                    ).apply {
                        projectionHighWaterRevision = 41uL
                        scanGate = ScanGate(refreshEntered, refreshRelease)
                    }
                val session =
                    ManagedEngineSession(
                        filesDir = filesDir,
                        capabilityRegistry = CapabilityRegistry(),
                        openAdapter = { request ->
                            testRustEngineAdapter(
                                if (request.workspace == null) {
                                    SessionFakeNativeEnginePort(NativeEngineSnapshot.AwaitingWorkspaceSelection)
                                } else {
                                    candidate
                                },
                            )
                        },
                        directorySettingsRepository = settings,
                        appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                        isContentUri = { it.startsWith("content://") },
                    )
                try {
                    refreshEntered.await(5, TimeUnit.SECONDS) shouldBe true

                    session.readiness.value shouldBe
                        EngineReadiness.Ready(coreRevision = 5uL, eventSequence = 8uL)
                    session.activeWorkspaceLocation.value shouldBe tree
                    checkNotNull(session.workspaceAuthority.value).projectionRevision shouldBe 41uL
                    session.projectionFreshness.value shouldBe
                        ProjectionFreshness.Refreshing(lastVerifiedRevision = 41uL)
                } finally {
                    refreshRelease.countDown()
                    advanceUntilIdle()
                    session.close()
                    filesDir.deleteRecursively()
                }
            }
        }

        test("given durable SAF projection when background refresh times out then Ready authority remains") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-saf-refresh-timeout").toFile()
                val tree = StorageLocation("content://com.lomo.documents/tree/primary%3ALomo")
                val refreshFailed = CountDownLatch(1)
                val settings = InMemoryDirectorySettingsRepository()
                settings.setLocation(StorageArea.ROOT, tree)
                val candidate =
                    SessionFakeNativeEnginePort(
                        NativeEngineSnapshot.Ready(coreRevision = 5uL, eventSequence = 8uL),
                    ).apply {
                        projectionHighWaterRevision = 41uL
                        safProjectionFailure =
                            ProjectionRebuildException(
                                failureCode = "platform_batch_deadline_exceeded",
                                failureCategory = "timeout",
                                diagnostic = "Platform batch deadline expired before Android execution",
                            )
                        onSafProjectionFailure = refreshFailed::countDown
                    }
                val session =
                    ManagedEngineSession(
                        filesDir = filesDir,
                        capabilityRegistry = CapabilityRegistry(),
                        openAdapter = { request ->
                            testRustEngineAdapter(
                                if (request.workspace == null) {
                                    SessionFakeNativeEnginePort(NativeEngineSnapshot.AwaitingWorkspaceSelection)
                                } else {
                                    candidate
                                },
                            )
                        },
                        directorySettingsRepository = settings,
                        appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                        isContentUri = { it.startsWith("content://") },
                    )
                try {
                    refreshFailed.await(5, TimeUnit.SECONDS) shouldBe true
                    advanceUntilIdle()

                    session.readiness.value shouldBe
                        EngineReadiness.Ready(coreRevision = 5uL, eventSequence = 8uL)
                    session.activeWorkspaceLocation.value shouldBe tree
                    checkNotNull(session.workspaceAuthority.value).projectionRevision shouldBe 41uL
                    session.projectionFreshness.value shouldBe
                        ProjectionFreshness.Stale(
                            lastVerifiedRevision = 41uL,
                            reasonCode = "platform_batch_deadline_exceeded",
                        )
                } finally {
                    session.close()
                    filesDir.deleteRecursively()
                }
            }
        }

        test("given candidate loses Ready during projection inspection when cold restore runs then recovery is preserved") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-saf-commit-recheck").toFile()
                val tree = StorageLocation("content://com.lomo.documents/tree/primary%3ALomo")
                val settings = InMemoryDirectorySettingsRepository()
                settings.setLocation(StorageArea.ROOT, tree)
                val nativeRecovery =
                    EngineFailureSnapshot(
                        category = "permission",
                        code = "saf_grant_revoked",
                        retryDisposition = "after_user_action",
                        diagnostic = "Persisted tree grant is no longer writable",
                    )
                val candidate =
                    SessionFakeNativeEnginePort(
                        NativeEngineSnapshot.Ready(coreRevision = 5uL, eventSequence = 8uL),
                    ).apply {
                        projectionHighWaterRevision = 41uL
                        onQueryMemos = {
                            snapshot = NativeEngineSnapshot.ReadOnlyRecovery(nativeRecovery)
                            emitInvalidation(coreRevision = 5uL, eventSequence = 9uL)
                        }
                    }
                val session =
                    ManagedEngineSession(
                        filesDir = filesDir,
                        capabilityRegistry = CapabilityRegistry(),
                        openAdapter = { request ->
                            testRustEngineAdapter(
                                if (request.workspace == null) {
                                    SessionFakeNativeEnginePort(NativeEngineSnapshot.AwaitingWorkspaceSelection)
                                } else {
                                    candidate
                                },
                            )
                        },
                        directorySettingsRepository = settings,
                        appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                        isContentUri = { it.startsWith("content://") },
                    )
                try {
                    val recovery =
                        session.readiness.value.shouldBeInstanceOf<EngineReadiness.ReadOnlyRecovery>()
                    recovery.code shouldBe "saf_grant_revoked"
                    recovery.category shouldBe EngineReadiness.FailureCategory.PERMISSION
                    session.activeWorkspaceLocation.value shouldBe null
                    session.workspaceAuthority.value shouldBe null
                } finally {
                    session.close()
                    filesDir.deleteRecursively()
                }
            }
        }

        test("given stale cold restore when a newer root is committed then it cannot replace the newer authority") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-stale-restore").toFile()
                val staleRoot = kotlin.io.path.createTempDirectory("ws-stale-restore").toFile()
                val committedRoot = kotlin.io.path.createTempDirectory("ws-newer-commit").toFile()
                try {
                    val settings = InMemoryDirectorySettingsRepository()
                    settings.setLocation(StorageArea.ROOT, StorageLocation(committedRoot.absolutePath))
                    settings.recoveredRootOverride = StorageLocation(staleRoot.absolutePath)
                    val session =
                        ManagedEngineSession(
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { request ->
                                val snapshot =
                                    if (request.workspace == null) {
                                        NativeEngineSnapshot.AwaitingWorkspaceSelection
                                    } else {
                                        NativeEngineSnapshot.Ready(coreRevision = 2uL, eventSequence = 4uL)
                                    }
                                testRustEngineAdapter(SessionFakeNativeEnginePort(snapshot))
                            },
                            directorySettingsRepository = settings,
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { false },
                        )

                    session.activeWorkspaceLocation.value shouldBe null
                    session.readiness.value shouldBe EngineReadiness.AwaitingWorkspaceSelection
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                    staleRoot.deleteRecursively()
                    committedRoot.deleteRecursively()
                }
            }
        }

        test("given soft Recovery open when activate runs then previous engine remains authoritative") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-soft").toFile()
                val previousRoot = kotlin.io.path.createTempDirectory("ws-prev").toFile()
                val candidateRoot = kotlin.io.path.createTempDirectory("ws-cand").toFile()
                try {
                    val closedPorts = mutableListOf<SessionFakeNativeEnginePort>()
                    val session =
                        ManagedEngineSession(
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { request ->
                                val snapshot =
                                    when {
                                        request.workspace == null ->
                                            NativeEngineSnapshot.AwaitingWorkspaceSelection
                                        request.workspace is NativeWorkspaceSelection.Direct &&
                                            request.workspace.rootPath.absolutePath ==
                                            previousRoot.absolutePath ->
                                            NativeEngineSnapshot.Ready(coreRevision = 7uL, eventSequence = 9uL)
                                        else ->
                                            NativeEngineSnapshot.ReadOnlyRecovery(
                                                EngineFailureSnapshot(
                                                    category = "permission",
                                                    code = "saf_grant_revoked",
                                                    retryDisposition = "after_user_action",
                                                    diagnostic = "grant missing",
                                                ),
                                            )
                                    }
                                val port = SessionFakeNativeEnginePort(snapshot)
                                closedPorts += port
                                testRustEngineAdapter(port)
                            },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { false },
                        )
                    session.activateWorkspace(StorageLocation(previousRoot.absolutePath))
                    session.readiness.value shouldBe
                        EngineReadiness.Ready(coreRevision = 7uL, eventSequence = 9uL)
                    val readyPortCloseBefore = closedPorts.last().portCloseCount

                    val error =
                        shouldThrow<WorkspaceActivationException> {
                            session.activateWorkspace(StorageLocation(candidateRoot.absolutePath))
                        }
                    error.recovery.code shouldBe "saf_grant_revoked"
                    session.readiness.value shouldBe
                        EngineReadiness.Ready(coreRevision = 7uL, eventSequence = 9uL)
                    // Previous Ready port must remain open; only the soft-failed candidate closed.
                    closedPorts[1].portCloseCount shouldBe readyPortCloseBefore
                    closedPorts.last().portCloseCount shouldBe 1
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                    previousRoot.deleteRecursively()
                    candidateRoot.deleteRecursively()
                }
            }
        }

        test("given soft candidate whose close throws when activate runs then the capability is still revoked") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-soft-close-fail").toFile()
                try {
                    val registry = CapabilityRegistry()
                    val tokens = mutableListOf<String>()
                    val session =
                        ManagedEngineSession(
                            filesDir = filesDir,
                            capabilityRegistry = registry,
                            openAdapter = { request ->
                                testRustEngineAdapter(
                                    safCandidatePort(request, tokens) {
                                        SessionFakeNativeEnginePort(
                                            NativeEngineSnapshot.ReadOnlyRecovery(
                                                EngineFailureSnapshot(
                                                    category = "permission",
                                                    code = "saf_grant_revoked",
                                                    retryDisposition = "after_user_action",
                                                    diagnostic = "grant missing",
                                                ),
                                            ),
                                        ).apply {
                                            closeFailure = IllegalStateException("candidate close refused")
                                        }
                                    },
                                )
                            },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { it.startsWith("content://") },
                        )

                    val error =
                        shouldThrow<WorkspaceActivationException> {
                            session.activateWorkspace(
                                StorageLocation("content://com.lomo.documents/tree/primary%3ALomo"),
                            )
                        }

                    error.recovery.code shouldBe "saf_grant_revoked"
                    error.suppressedExceptions.single().message shouldBe "candidate close refused"
                    shouldThrow<CapabilityRegistryException> { registry.resolve(tokens.single()) }
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                }
            }
        }

        test("given previous engine close failure when candidate promotes then neither engine stays published") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-retire-fail").toFile()
                try {
                    val registry = CapabilityRegistry()
                    val tokens = mutableListOf<String>()
                    val ports = mutableListOf<SessionFakeNativeEnginePort>()
                    val session =
                        ManagedEngineSession(
                            filesDir = filesDir,
                            capabilityRegistry = registry,
                            openAdapter = { request ->
                                testRustEngineAdapter(
                                    safCandidatePort(request, tokens) {
                                        SessionFakeNativeEnginePort(
                                            NativeEngineSnapshot.Ready(coreRevision = 1uL, eventSequence = 1uL),
                                        )
                                    }.also(ports::add),
                                )
                            },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { it.startsWith("content://") },
                        )
                    session.activateWorkspace(
                        StorageLocation("content://com.lomo.documents/tree/primary%3AFirst"),
                    )
                    ports[1].closeFailure = IllegalStateException("previous close refused")

                    val error =
                        shouldThrow<IllegalStateException> {
                            session.activateWorkspace(
                                StorageLocation("content://com.lomo.documents/tree/primary%3ASecond"),
                            )
                        }

                    error.message shouldBe "previous close refused"
                    val recovery =
                        session.readiness.value.shouldBeInstanceOf<EngineReadiness.ReadOnlyRecovery>()
                    recovery.code shouldBe "workspace_retire_failed"
                    // The candidate never becomes authoritative and is released with its capability.
                    ports.last().portCloseCount shouldBe 1
                    tokens.size shouldBe 2
                    tokens.forEach { token ->
                        shouldThrow<CapabilityRegistryException> { registry.resolve(token) }
                    }
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                }
            }
        }

        test("given active engine close failure when session closes then revoke and ShuttingDown still happen") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-close-fail").toFile()
                try {
                    val registry = CapabilityRegistry()
                    val tokens = mutableListOf<String>()
                    val ports = mutableListOf<SessionFakeNativeEnginePort>()
                    val session =
                        ManagedEngineSession(
                            filesDir = filesDir,
                            capabilityRegistry = registry,
                            openAdapter = { request ->
                                testRustEngineAdapter(
                                    safCandidatePort(request, tokens) {
                                        SessionFakeNativeEnginePort(
                                            NativeEngineSnapshot.Ready(coreRevision = 1uL, eventSequence = 1uL),
                                        )
                                    }.also(ports::add),
                                )
                            },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { it.startsWith("content://") },
                        )
                    session.activateWorkspace(
                        StorageLocation("content://com.lomo.documents/tree/primary%3ALomo"),
                    )
                    ports.last().closeFailure = IllegalStateException("engine close refused")

                    val error = shouldThrow<IllegalStateException> { session.close() }

                    error.message shouldBe "engine close refused"
                    session.readiness.value shouldBe EngineReadiness.ShuttingDown
                    shouldThrow<CapabilityRegistryException> { registry.resolve(tokens.single()) }
                } finally {
                    filesDir.deleteRecursively()
                }
            }
        }

        test("given cold restore hard open failure when resnapshot runs then Recovery authority holds") {            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-cold-fail").toFile()
                try {
                    val settings = InMemoryDirectorySettingsRepository()
                    settings.setLocation(StorageArea.ROOT, StorageLocation("/tmp/missing-root"))
                    var bootstrapPort: SessionFakeNativeEnginePort? = null
                    val session =
                        ManagedEngineSession(
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { request ->
                                if (request.workspace != null) {
                                    error("native open refused")
                                }
                                val port =
                                    SessionFakeNativeEnginePort(NativeEngineSnapshot.AwaitingWorkspaceSelection)
                                bootstrapPort = port
                                testRustEngineAdapter(port)
                            },
                            directorySettingsRepository = settings,
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { false },
                        )

                    val recovery = session.readiness.value.shouldBeInstanceOf<EngineReadiness.ReadOnlyRecovery>()
                    recovery.code shouldBe "workspace_open_failed"
                    recovery.diagnostic.shouldContain("native open refused")

                    // Bootstrap still Awaiting underneath; resnapshot must not overwrite Recovery.
                    bootstrapPort!!.snapshot = NativeEngineSnapshot.AwaitingWorkspaceSelection
                    session.resnapshot()
                    session.readiness.value.shouldBeInstanceOf<EngineReadiness.ReadOnlyRecovery>()
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                }
            }
        }

        test("given Ready session when scan is routed then the same active port owns every call") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-route").toFile()
                val workspace = kotlin.io.path.createTempDirectory("ws-route").toFile()
                try {
                    val openedPorts = mutableListOf<SessionFakeNativeEnginePort>()
                    val session =
                        ManagedEngineSession(
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { request ->
                                val port =
                                    SessionFakeNativeEnginePort(
                                        if (request.workspace == null) {
                                            NativeEngineSnapshot.AwaitingWorkspaceSelection
                                        } else {
                                            NativeEngineSnapshot.Ready(1uL, 2uL)
                                        },
                                    )
                                openedPorts += port
                                testRustEngineAdapter(port)
                            },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { false },
                        )
                    session.activateWorkspace(StorageLocation(workspace.absolutePath))

                    val jobId = session.startWorkspaceScan(pageSize = 16u)
                    session.driveJob(jobId) shouldBe NativeJobStep.Completed
                    session.readWorkspaceScanPage(jobId).items shouldBe emptyList()

                    openedPorts.size shouldBe 2 // bootstrap + selected workspace; never a scan engine
                    openedPorts.first().workspaceCalls shouldBe emptyList()
                    openedPorts.last().workspaceCalls shouldBe
                        listOf("start-scan", "poll:$jobId", "read-scan:$jobId")
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                    workspace.deleteRecursively()
                }
            }
        }

        test("given memo command target on first scan page then later pages are not materialized") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-bounded-command").toFile()
                val workspace = kotlin.io.path.createTempDirectory("ws-bounded-command").toFile()
                try {
                    var selectedPort: SessionFakeNativeEnginePort? = null
                    val session =
                        ManagedEngineSession(
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { request ->
                                SessionFakeNativeEnginePort(
                                    if (request.workspace == null) NativeEngineSnapshot.AwaitingWorkspaceSelection
                                    else NativeEngineSnapshot.Ready(1uL, 2uL),
                                ).also { if (request.workspace != null) selectedPort = it }
                                    .let(::testRustEngineAdapter)
                            },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { false },
                        )
                    session.activateWorkspace(StorageLocation(workspace.absolutePath))
                    val activePort = checkNotNull(selectedPort)
                    activePort.scanPages += WorkspaceScanPageSnapshot(
                        items = listOf(workspaceSnapshot("2026_08_05.md", "target", "a", "body")),
                        nextCursor = "later",
                    )
                    session.removeMemo(null, "2026_08_05.md", "target") shouldBe true
                    activePort.scanPageReadCount shouldBe 1
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                    workspace.deleteRecursively()
                }
            }
        }

        test("given in-flight scan when workspace switches then previous port closes after lease release") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-lease").toFile()
                val firstRoot = kotlin.io.path.createTempDirectory("ws-lease-first").toFile()
                val secondRoot = kotlin.io.path.createTempDirectory("ws-lease-second").toFile()
                try {
                    val openedPorts = mutableListOf<SessionFakeNativeEnginePort>()
                    val candidateReturned = CountDownLatch(1)
                    val session =
                        ManagedEngineSession(
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { request ->
                                val port =
                                    SessionFakeNativeEnginePort(
                                        if (request.workspace == null) {
                                            NativeEngineSnapshot.AwaitingWorkspaceSelection
                                        } else {
                                            NativeEngineSnapshot.Ready(1uL, 1uL)
                                        },
                                    )
                                openedPorts += port
                                testRustEngineAdapter(port).also {
                                    if (openedPorts.size == 3) candidateReturned.countDown()
                                }
                            },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { false },
                        )
                    session.activateWorkspace(StorageLocation(firstRoot.absolutePath))
                    val previous = openedPorts[1]
                    val scanEntered = CountDownLatch(1)
                    val scanRelease = CountDownLatch(1)
                    previous.scanGate = ScanGate(scanEntered, scanRelease)
                    val scanThread = Thread { session.startWorkspaceScan(pageSize = 16u) }
                    scanThread.start()
                    scanEntered.await(5, TimeUnit.SECONDS) shouldBe true

                    val switching =
                        async(Dispatchers.Default) {
                            session.activateWorkspace(StorageLocation(secondRoot.absolutePath))
                        }
                    candidateReturned.await(5, TimeUnit.SECONDS) shouldBe true

                    previous.portCloseCount shouldBe 0
                    switching.isCompleted shouldBe false
                    scanRelease.countDown()
                    scanThread.join(5_000)
                    switching.await()

                    previous.portCloseCount shouldBe 1
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                    firstRoot.deleteRecursively()
                    secondRoot.deleteRecursively()
                }
            }
        }

        test("given SAF memo mutations when routed through the session then platform and projection commits form one closed loop") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-saf-mutations").toFile()
                val tree = StorageLocation("content://com.lomo.documents/tree/primary%3ALomo")
                val epoch = 1_754_300_000_000L
                val local = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault())
                val createdTimePart =
                    local.toLocalTime().format(StorageTimestampFormats.formatter(StorageTimestampFormats.DEFAULT_PATTERN))
                val datePath =
                    "${local.toLocalDate().format(StorageFilenameFormats.formatter(StorageFilenameFormats.DEFAULT_PATTERN))}.md"
                val created =
                    workspaceSnapshot(
                        path = datePath,
                        identity = "${datePath.removeSuffix(".md")}_${createdTimePart}_0",
                        fingerprint = "b".repeat(64),
                        content = "created",
                        timePart = createdTimePart,
                    )
                val existing = workspaceSnapshot("2026_08_04.md", "2026_08_04_10:00:00_0", "a".repeat(64), "old")
                val updated = workspaceSnapshot(existing.path, existing.identity, "c".repeat(64), "new")
                val candidate = SessionFakeNativeEnginePort(NativeEngineSnapshot.Ready(1uL, 1uL))
                candidate.scanPages.add(WorkspaceScanPageSnapshot(emptyList(), null))
                candidate.scanPages.add(WorkspaceScanPageSnapshot(listOf(created), null))
                candidate.scanPages.add(WorkspaceScanPageSnapshot(listOf(existing), null))
                candidate.scanPages.add(WorkspaceScanPageSnapshot(listOf(updated), null))
                candidate.scanPages.add(WorkspaceScanPageSnapshot(listOf(existing), null))
                candidate.scanPages.add(WorkspaceScanPageSnapshot(emptyList(), null))
                candidate.scanPages.add(WorkspaceScanPageSnapshot(listOf(existing), null))
                try {
                    val session =
                        ManagedEngineSession(
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { request ->
                                if (request.workspace == null) {
                                    testRustEngineAdapter(SessionFakeNativeEnginePort(NativeEngineSnapshot.AwaitingWorkspaceSelection))
                                } else {
                                    testRustEngineAdapter(candidate)
                                }
                            },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { it.startsWith("content://") },
                        )
                    session.activateWorkspace(tree)

                    session.applyMemoCommand(
                        bridgeMemoCommand(
                            operationId = "saf-create",
                            kind = com.lomo.nativebridge.StoreMemoCommandKind.CREATE,
                            memoId = "client-id",
                            expectedRevision = 0uL,
                            content = "created",
                            chronologyEpochMs = epoch,
                        ),
                    )
                    candidate.lastDocumentCommand.shouldBeInstanceOf<WorkspaceNativeCommandSpec.Create>()
                    candidate.lastExpectedState shouldBe WorkspaceNativeExpectedState.Absent
                    candidate.safProjectionCommits.last().second?.memoId shouldBe created.identity

                    session.applyMemoCommand(
                        bridgeMemoCommand(
                            operationId = "saf-update",
                            kind = com.lomo.nativebridge.StoreMemoCommandKind.UPDATE,
                            memoId = existing.identity,
                            expectedRevision = 1uL,
                            expectedFingerprint = existing.fingerprint,
                            content = "new",
                        ),
                    )
                    candidate.lastDocumentCommand.shouldBeInstanceOf<WorkspaceNativeCommandSpec.Replace>()
                    candidate.lastExpectedState shouldBe WorkspaceNativeExpectedState.Match(existing.fingerprint)
                    candidate.safProjectionCommits.last().second?.fileFingerprint shouldBe updated.fingerprint

                    session.applyMemoCommand(
                        bridgeMemoCommand(
                            operationId = "saf-delete",
                            kind = com.lomo.nativebridge.StoreMemoCommandKind.DELETE,
                            memoId = existing.identity,
                            expectedRevision = 1uL,
                            expectedFingerprint = existing.fingerprint,
                        ),
                    )
                    candidate.lastDocumentCommand.shouldBeInstanceOf<WorkspaceNativeCommandSpec.Remove>()
                    candidate.safProjectionCommits.last().second shouldBe null

                    candidate.lastDocumentCommand = null
                    session.applyMemoCommand(
                        bridgeMemoCommand(
                            operationId = "saf-pin",
                            kind = com.lomo.nativebridge.StoreMemoCommandKind.PIN,
                            memoId = existing.identity,
                            expectedRevision = 1uL,
                            expectedFingerprint = existing.fingerprint,
                            pin = true,
                        ),
                    )
                    candidate.lastDocumentCommand shouldBe null
                    candidate.directApplyCount shouldBe 0
                    candidate.safProjectionCommits.size shouldBe 4
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                }
            }
        }
    }
}

private class InMemoryDirectorySettingsRepository : DirectorySettingsRepository {
    private val locations =
        MutableStateFlow<MutableMap<StorageArea, StorageLocation?>>(mutableMapOf())
    private val displayNames =
        MutableStateFlow<MutableMap<StorageArea, String?>>(mutableMapOf())
    private var pendingTransition: com.lomo.domain.model.WorkspaceRootTransition? = null
    private var nextTransitionId: Int = 1
    var recoveredRootOverride: StorageLocation? = null

    fun setLocation(
        area: StorageArea,
        location: StorageLocation?,
    ) {
        locations.value = locations.value.toMutableMap().also { values -> values[area] = location }
    }

    override fun observeLocation(area: StorageArea): Flow<StorageLocation?> =
        locations.map { values -> values[area] }

    override suspend fun currentLocation(area: StorageArea): StorageLocation? = locations.value[area]

    override suspend fun applyLocation(update: StorageAreaUpdate) {
        setLocation(area = update.area, location = update.location)
    }

    override fun observeDisplayName(area: StorageArea): Flow<String?> =
        displayNames.map { values -> values[area] }

    override suspend fun prepareRootTransition(
        candidate: StorageLocation,
    ): com.lomo.domain.model.WorkspaceRootTransition {
        check(pendingTransition == null) { "Workspace transition already pending" }
        return com.lomo.domain.model.WorkspaceRootTransition(
            id = "test-transition-${nextTransitionId++}",
            previous = currentRootLocation(),
            candidate = candidate,
            phase = com.lomo.domain.model.WorkspaceRootTransitionPhase.PREPARED,
        ).also { pendingTransition = it }
    }

    override suspend fun markRootTransitionActivated(
        transitionId: String,
    ): com.lomo.domain.model.WorkspaceRootTransition {
        val current = requirePendingTransition(transitionId)
        check(current.phase == com.lomo.domain.model.WorkspaceRootTransitionPhase.PREPARED)
        return current.copy(phase = com.lomo.domain.model.WorkspaceRootTransitionPhase.ACTIVATED)
            .also { pendingTransition = it }
    }

    override suspend fun commitRootTransition(transitionId: String) {
        val current = requirePendingTransition(transitionId)
        check(current.phase == com.lomo.domain.model.WorkspaceRootTransitionPhase.ACTIVATED)
        setLocation(StorageArea.ROOT, current.candidate)
        pendingTransition = null
    }

    override suspend fun rollbackRootTransition(transitionId: String) {
        requirePendingTransition(transitionId)
        pendingTransition = null
    }

    override suspend fun pendingRootTransition(): com.lomo.domain.model.WorkspaceRootTransition? =
        pendingTransition

    override suspend fun recoverRootLocation(): StorageLocation? {
        pendingTransition = null
        return recoveredRootOverride ?: currentRootLocation()
    }

    private fun requirePendingTransition(
        transitionId: String,
    ): com.lomo.domain.model.WorkspaceRootTransition {
        val current = checkNotNull(pendingTransition) { "Workspace transition is missing" }
        check(current.id == transitionId) { "Workspace transition id mismatch" }
        return current
    }
}

private class SessionFakeNativeEnginePort(
    initialSnapshot: NativeEngineSnapshot,
) : WorkspaceNativeEnginePort {
    var scanPageReadCount: Int = 0
    var lanStartCount: Int = 0
    var lastLanNetworkFacts: LanNetworkFacts? = null
    var lanSessionBegins: Int = 0
    var preparedLanBatchId: String? = null
    var rejectedLanBatchId: String? = null
    var sentLanChunk: ByteArray? = null
    var committedLanBatchId: String? = null
    var committedLanItemIndex: UInt? = null
    val batchPreview =
        LanBatchPreview(
            batchId = "batch-managed",
            senderDeviceId = "a".repeat(64),
            senderDisplayName = "Tablet",
            itemCount = 1u,
            attachmentCount = 0u,
            totalBytes = 4uL,
            titles = listOf("Preview"),
        )
    val sessionChallenge =
        LanSessionChallenge(
            sessionId = "b".repeat(32),
            peerDeviceId = "a".repeat(64),
            transcriptToSign = byteArrayOf(1, 2, 3),
            deadlineMs = 61_000,
        )
    val runtimeInbox =
        LanRuntimeInbox(
            pairingChallenges = emptyList(),
            sessionChallenges = listOf(sessionChallenge),
            activeSessions = emptyList(),
            pendingBatches =
                listOf(
                    LanPendingBatch(
                        sessionId = sessionChallenge.sessionId,
                        preview = batchPreview,
                    ),
                ),
            batchRecoveries =
                listOf(
                    LanBatchRecovery(
                        sessionId = sessionChallenge.sessionId,
                        preview = batchPreview,
                        decision = LanReceivedBatchDecision.Approved,
                        items =
                            listOf(
                                LanReceivedItemRecovery.Committed(
                                    itemId = "item-managed",
                                    itemIndex = 0u,
                                    memoId = "memo-managed",
                                ),
                            ),
                    ),
                ),
            committableItems = emptyList(),
            outgoingBatches = emptyList(),
        )

    override fun updateLanNetworkSnapshot(snapshot: LanNetworkFacts) {
        lastLanNetworkFacts = snapshot
    }

    override fun updateLanDiscoverySnapshot(snapshot: LanDiscoveryFacts) = Unit

    override fun startLanService(): LanServiceState {
        lanStartCount += 1
        return LanServiceState(LanServicePhase.Listening, "127.0.0.1:43123")
    }

    override fun stopLanService(): LanServiceState =
        LanServiceState(LanServicePhase.Stopped, null)

    override fun listLanDiscoveredPeers(): List<LanDiscoveredPeer> = emptyList()

    override fun lanTransferShape(): LanTransferShape = LanTransferShape(bodySlot = 0u, chunkPlaintextBytes = 0u)

    override fun configureLanIdentity(identity: LanDeviceIdentity): LanLocalIdentity =
        LanLocalIdentity(deviceId = "c".repeat(64), displayName = identity.displayName)

    override fun beginLanPairing(
        peerDeviceId: String,
        nowMs: Long,
        ttlMs: Long,
    ): LanPairingChallenge = error("pairing not expected")

    override fun pollLanListener(nowMs: Long): LanRuntimeInbox = runtimeInbox

    override fun lanRuntimeInbox(): LanRuntimeInbox = runtimeInbox

    override fun lanPairingChallenge(pairingId: String): LanPairingChallenge =
        error("pairing not expected")

    override fun confirmLanPairing(
        pairingId: String,
        signature: ByteArray,
        nowMs: Long,
    ) = Unit

    override fun declineLanPairing(pairingId: String) = Unit

    override fun beginLanSession(
        peerDeviceId: String,
        nowMs: Long,
        ttlMs: Long,
    ): LanSessionChallenge {
        lanSessionBegins += 1
        return sessionChallenge
    }

    override fun lanSessionChallenge(sessionId: String): LanSessionChallenge = sessionChallenge

    override fun confirmLanSession(
        sessionId: String,
        signature: ByteArray,
        nowMs: Long,
    ) = Unit

    override fun lanSessionState(sessionId: String): LanSessionState =
        LanSessionState(
            sessionId = sessionChallenge.sessionId,
            peerDeviceId = sessionChallenge.peerDeviceId,
            phase = LanSessionPhase.Authenticated,
        )

    override fun prepareLanBatch(
        sessionId: String,
        batchId: String,
        items: List<LanSendItemPlan>,
    ) {
        preparedLanBatchId = batchId
    }

    override fun lanBatchPreview(batchId: String): LanBatchPreview = batchPreview

    override fun approveLanBatch(
        sessionId: String,
        batchId: String,
        nowMs: Long,
        ttlMs: Long,
    ) = Unit

    override fun rejectLanBatch(
        sessionId: String,
        batchId: String,
        rejectedAtMs: Long,
    ) {
        rejectedLanBatchId = batchId
    }

    override fun sendLanBatchChunk(
        sessionId: String,
        batchId: String,
        itemIndex: UInt,
        attachmentSlot: UInt,
        chunkIndex: UInt,
        plaintext: ByteArray,
    ) {
        sentLanChunk = plaintext
    }

    override fun lanUnconfirmedBatchChunks(
        batchId: String,
        itemIndex: UInt,
        attachmentSlot: UInt,
    ): List<UInt> = listOf(0u)

    override fun commitReceivedLanItem(
        batchId: String,
        itemIndex: UInt,
        nowMs: Long,
    ): String {
        committedLanBatchId = batchId
        committedLanItemIndex = itemIndex
        return "memo-received"
    }

    override fun listLanPeers(): LanPeerPage = LanPeerPage(emptyList(), 0u)

    override fun revokeLanPeer(
        deviceId: String,
        revokedAtMs: Long,
    ): LanPeerPage = error("revoke not expected")

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
    var portCloseCount: Int = 0
    var closeFailure: Throwable? = null
    var renderCallCount: Int = 0
    val workspaceCalls = mutableListOf<String>()
    var scanGate: ScanGate? = null
    var scanPages: ArrayDeque<WorkspaceScanPageSnapshot> = ArrayDeque()
    var projectionPages: ArrayDeque<WorkspaceProjectionScanPageSnapshot> = ArrayDeque()
    val projectionEvents = mutableListOf<String>()
    var documentTerminal: NativeJobStep = NativeJobStep.Completed
    var lastDocumentCommand: WorkspaceNativeCommandSpec? = null
    var lastExpectedState: WorkspaceNativeExpectedState? = null
    var lastExpectedFingerprint: String? = null
    var directApplyCount: Int = 0
    val safProjectionCommits =
        mutableListOf<
            Pair<
                com.lomo.nativebridge.StoreMemoCommand,
                com.lomo.nativebridge.StoreSafMemoProjection?,
            >,
        >()
    var rebuildCount: Int = 0
    var onRebuild: (() -> Unit)? = null
    var safProjectionRebuildCount: Int = 0
    var safProjectionFailure: Throwable? = null
    var onSafProjectionFailure: (() -> Unit)? = null
    var projectedSafMemos: List<SafMemoProjectionSnapshot> = emptyList()
    var onSafProjectionRebuild: (() -> Unit)? = null
    var projectionHighWaterRevision: ULong = 0uL
    var onQueryMemos: (() -> Unit)? = null
    private var listener: ((NativeCoreEvent) -> Unit)? = null

    fun emitInvalidation(
        coreRevision: ULong,
        eventSequence: ULong,
    ) {
        listener?.invoke(NativeCoreEvent(coreRevision, eventSequence))
    }

    override fun state(): NativeEngineSnapshot = snapshot

    override fun subscribe(listener: (NativeCoreEvent) -> Unit): NativeEngineSubscription {
        this.listener = listener
        return NativeEngineSubscription {
            this.listener = null
        }
    }

    override fun pollJob(jobId: String): NativeJobStep {
        workspaceCalls += "poll:$jobId"
        return if (jobId == "document-job") documentTerminal else NativeJobStep.Completed
    }

    override fun submitPlatformResult(
        jobId: String,
        result: com.lomo.nativebridge.PlatformBatchResult,
    ): NativeJobStep = NativeJobStep.Completed

    override fun renderMarkdown(
        content: String,
        schemaVersion: UInt,
    ): MarkdownRenderDocument {
        renderCallCount += 1
        return MarkdownRenderDocument(
            sourceByteLength = content.encodeToByteArray().size.toULong(),
            plainText = "rendered:$content",
            tagNames = emptyList(),
            attachmentDestinations = emptyList(),
            blocks = emptyList(),
        )
    }

    override fun startWorkspaceScan(
        pageSize: UInt,
        cursor: String?,
        rootPath: String?,
        deadlineMillis: ULong,
    ): String {
        workspaceCalls += "start-scan"
        scanGate?.let { gate ->
            gate.entered.countDown()
            check(gate.release.await(5, TimeUnit.SECONDS)) { "scan lease was not released" }
        }
        return "scan-job"
    }

    override fun readWorkspaceScanPage(jobId: String): WorkspaceScanPageSnapshot {
        scanPageReadCount++
        workspaceCalls += "read-scan:$jobId"
        return scanPages.removeFirstOrNull()
            ?: WorkspaceScanPageSnapshot(items = emptyList(), nextCursor = null)
    }

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
    }

    override fun finishSafProjectionRebuild(rebuildId: String): com.lomo.nativebridge.StoreRebuildResult {
        projectionEvents += "finish"
        safProjectionRebuildCount += 1
        safProjectionFailure?.let { failure ->
            onSafProjectionFailure?.invoke()
            throw failure
        }
        onSafProjectionRebuild?.invoke()
        projectionHighWaterRevision += 1uL
        return com.lomo.nativebridge.StoreRebuildResult(
            memosIndexed = 0uL,
            fileCount = 0uL,
            attachmentCount = 0uL,
            workspaceDigest = "a".repeat(64),
            storeDigest = "a".repeat(64),
            corruptLomoIsolated = 0uL,
            highWaterRevision = projectionHighWaterRevision,
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
    ): String {
        lastExpectedState = expectedState
        lastExpectedFingerprint = (expectedState as? WorkspaceNativeExpectedState.Match)?.fingerprint
        lastDocumentCommand = command
        return "document-job"
    }

    override fun readWorkspaceDocumentCommandResult(jobId: String): WorkspaceNativeCommandResultSnapshot =
        WorkspaceNativeCommandResultSnapshot(
            path = "2026-07-20.md",
            resultFingerprint = "b".repeat(64),
            bytesWritten = 22uL,
        )

    override fun queryMemos(
        query: com.lomo.nativebridge.StoreMemoQuery,
        cursor: com.lomo.nativebridge.StorePageCursor?,
        pageSize: UInt,
    ): com.lomo.nativebridge.StoreMemoPage {
        onQueryMemos?.also { callback ->
            onQueryMemos = null
            callback()
        }
        return com.lomo.nativebridge.StoreMemoPage(
            items = emptyList(),
            nextCursor = null,
            highWaterRevision = projectionHighWaterRevision,
            queryFingerprint = "fake-query",
        )
    }

    override fun listHistoryAttachmentRefs(): List<com.lomo.nativebridge.StoreHistoryAttachmentRef> =
        emptyList()

    override fun getMemo(memoId: String): com.lomo.nativebridge.StoreMemoSnapshot? =
        error("store get not expected")

    override fun sidebarProjection(): com.lomo.nativebridge.StoreSidebarProjection =
        error("sidebar projection not expected")

    override fun applyMemoCommand(
        command: com.lomo.nativebridge.StoreMemoCommand,
    ): com.lomo.nativebridge.StoreMemoCommit {
        directApplyCount += 1
        return fakeCommit(command)
    }

    override fun commitSafProjectionMutation(
        command: com.lomo.nativebridge.StoreMemoCommand,
        projection: com.lomo.nativebridge.StoreSafMemoProjection?,
    ): com.lomo.nativebridge.StoreMemoCommit {
        safProjectionCommits += command to projection
        return fakeCommit(command)
    }

    override fun startRebuild(batchSize: UInt): com.lomo.nativebridge.StoreRebuildResult {
        rebuildCount += 1
        onRebuild?.invoke() ?: error("store rebuild not expected")
        return com.lomo.nativebridge.StoreRebuildResult(
            memosIndexed = 2uL,
            fileCount = 1uL,
            attachmentCount = 0uL,
            workspaceDigest = "a".repeat(64),
            storeDigest = "a".repeat(64),
            corruptLomoIsolated = 0uL,
            highWaterRevision = 3uL,
        )
    }

    private fun fakeCommit(
        command: com.lomo.nativebridge.StoreMemoCommand,
    ): com.lomo.nativebridge.StoreMemoCommit =
        com.lomo.nativebridge.StoreMemoCommit(
            operationId = command.operationId,
            memoId = command.memoId,
            coreRevision = 2uL,
            eventSequence = 2uL,
            contentRevision = command.expectedRevision + 1uL,
            fileFingerprint = "c".repeat(64),
            scopes = listOf("memo:${command.memoId}"),
            idempotentReplay = false,
        )

    override fun close() {
        portCloseCount += 1
        closeFailure?.let { throw it }
    }
}

private data class ScanGate(
    val entered: CountDownLatch,
    val release: CountDownLatch,
)

private fun bridgeMemoCommand(
    operationId: String,
    kind: com.lomo.nativebridge.StoreMemoCommandKind,
    memoId: String,
    expectedRevision: ULong,
    expectedFingerprint: String? = null,
    content: String? = null,
    pin: Boolean? = null,
    chronologyEpochMs: Long? = null,
): com.lomo.nativebridge.StoreMemoCommand =
    com.lomo.nativebridge.StoreMemoCommand(
        operationId = operationId,
        kind = kind,
        memoId = memoId,
        expectedRevision = expectedRevision,
        expectedFingerprint = expectedFingerprint,
        content = content,
        tags = emptyList(),
        pin = pin,
        pendingPromotes = emptyList(),
        chronologyEpochMs = chronologyEpochMs,
    )

private fun workspaceSnapshot(
    path: String,
    identity: String,
    fingerprint: String,
    content: String,
    timePart: String = "10:00:00",
): WorkspaceMemoSummarySnapshot =
    WorkspaceMemoSummarySnapshot(
        path = path,
        identity = identity,
        timePart = timePart,
        fingerprint = fingerprint,
        tags = emptyList(),
        attachments = emptyList(),
        reminders = emptyList(),
        content = content,
        bodyStart = 0uL,
        bodyEnd = content.encodeToByteArray().size.toULong(),
        startLine = 0u,
        endLine = 1u,
    )

/**
 * Bootstrap requests get an Awaiting port; SAF candidate requests record their rotated token and
 * build the scenario's candidate port.
 */
private fun safCandidatePort(
    request: NativeEngineOpenRequest,
    tokens: MutableList<String>,
    candidate: () -> SessionFakeNativeEnginePort,
): SessionFakeNativeEnginePort {
    val saf =
        request.workspace as? NativeWorkspaceSelection.Saf
            ?: return SessionFakeNativeEnginePort(NativeEngineSnapshot.AwaitingWorkspaceSelection)
    tokens += saf.capabilityToken
    return candidate()
}

private fun testRustEngineAdapter(port: SessionFakeNativeEnginePort): RustEngineAdapter =
    RustEngineAdapter.acquire(
        native = port,
        platformBatchRunner =
            PlatformBatchRunner(
                native = port,
                executor =
                    AndroidPlatformActionExecutor(
                        access = PlatformActionAccess { error("platform action not expected") },
                        currentTimeMillis = { 0L },
                    ),
            ),
    )

private fun readyTaskPort(): SessionFakeNativeEnginePort =
    SessionFakeNativeEnginePort(
        NativeEngineSnapshot.Ready(coreRevision = 1uL, eventSequence = 1uL),
    ).also { port ->
        val common =
            WorkspaceMemoSummarySnapshot(
                path = "2026-07-20.md",
                identity = "2026-07-20_10:00:00_0",
                timePart = "10:00:00",
                fingerprint = "a".repeat(64),
                tags = emptyList(),
                attachments = emptyList(),
                reminders = emptyList(),
                content = "- [ ] task",
                bodyStart = 11uL,
                bodyEnd = 22uL,
                startLine = 0u,
                endLine = 1u,
            )
        port.scanPages.add(WorkspaceScanPageSnapshot(listOf(common), null))
        port.scanPages.add(
            WorkspaceScanPageSnapshot(
                listOf(common.copy(content = "- [x] task", fingerprint = "b".repeat(64))),
                null,
            ),
        )
    }

private fun readyReminderPort(): SessionFakeNativeEnginePort =
    SessionFakeNativeEnginePort(
        NativeEngineSnapshot.Ready(coreRevision = 1uL, eventSequence = 1uL),
    ).also { port ->
        val scanned =
            WorkspaceMemoSummarySnapshot(
                path = "2026-07-20.md",
                identity = "2026-07-20_10:00:00_0",
                timePart = "10:00:00",
                fingerprint = "a".repeat(64),
                tags = emptyList(),
                attachments = emptyList(),
                reminders = listOf(reminderSnapshot()),
                content = "@2026-07-20-09:30x2",
                bodyStart = 11uL,
                bodyEnd = 33uL,
                startLine = 0u,
                endLine = 1u,
            )
        repeat(2) { port.scanPages.add(WorkspaceScanPageSnapshot(listOf(scanned), null)) }
        port.scanPages.add(
            WorkspaceScanPageSnapshot(
                listOf(scanned.copy(content = "done", fingerprint = "b".repeat(64), reminders = emptyList())),
                null,
            ),
        )
    }

private fun reminderSnapshot(): WorkspaceReminderReferenceSnapshot =
    WorkspaceReminderReferenceSnapshot(
        opaqueId = "reminder-id",
        revision = "a".repeat(64),
        memoIdentity = "2026-07-20_10:00:00_0",
        sourceStart = 11uL,
        sourceEnd = 33uL,
        tokenFingerprint = "c".repeat(64),
        token = "@2026-07-20-09:30x2",
        dueAtLocal = "2026-07-20-09:30",
        repeatCount = 2u,
        firedCount = 0u,
        done = false,
        intervalMinutes = 10u,
        recurrenceCode = "",
    )

@OptIn(ExperimentalCoroutinesApi::class)
private fun readySession(
    filesDir: java.io.File,
    port: SessionFakeNativeEnginePort,
    scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
): ManagedEngineSession =
    ManagedEngineSession(
        filesDir = filesDir,
        capabilityRegistry = CapabilityRegistry(),
        openAdapter = { testRustEngineAdapter(port) },
        directorySettingsRepository = InMemoryDirectorySettingsRepository(),
        appScope = CoroutineScope(UnconfinedTestDispatcher(scheduler)),
        isContentUri = { false },
    )
