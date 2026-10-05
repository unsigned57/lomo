package com.lomo.data.engine

import com.lomo.domain.model.ProjectionFreshness

/*
 * Behavior Contract:
 * - Unit under test: ManagedEngineSession.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: cold-start Opening until the owned startup coroutine opens either the persisted
 *   workspace engine or a lazy bootstrap; activate installs only Ready candidates and
 *   only closes the previous engine after Ready install; soft Recovery / hard open failure keep
 *   previous authority; cold-restore hard failure freezes Recovery authority without paying for a
 *   bootstrap host; clearWorkspace serializes under the activation mutex.
 *
 * Scenarios:
 * - Given no configured root, when the session starts, then readiness is AwaitingWorkspaceSelection.
 * - Given a paused dispatcher, when the session is constructed, then readiness is Opening and no
 *   native engine has been acquired yet.
 * - Given a projection-only process duty, when the session is constructed and the dispatcher
 *   drains, then readiness stays Opening and no native engine is acquired.
 * - Given a persisted Direct root, when cold restore completes, then exactly one workspace engine
 *   is opened and bootstrap is never acquired.
 * - Given bootstrap engine acquisition fails, when the session is constructed, then the graph
 *   remains available in structured ReadOnlyRecovery without a placeholder adapter.
 * - Given native acquisition returns a typed EngineError, when the session enters recovery, then
 *   its category, code, retry disposition and diagnostic reach the UI boundary unchanged.
 * - Given SQLite integrity recovery on cold restore, when the user requests derived-index rebuild,
 *   then the recovery candidate rebuilds only the Rust projection and the same workspace reopens
 *   Ready without a Kotlin database fallback.
 * - Given a committed SAF root with a durable projection, when cold restore runs, then Ready and
 *   Verified are published together and Kotlin does not start a second StoreHandle scan.
 * - Given a candidate loses Ready while its durable projection revision is inspected, when cold
 *   restore reaches the commit boundary, then the candidate is rejected with its structured Rust
 *   recovery and no workspace authority is published.
 * - Given a committed Direct root, when a lower-water workspace is activated, then the
 *   invalidation bus reanchors and the next store commit publishes.
 * - Given open throws, when activate fails, then previous readiness remains and candidate is not
 *   installed.
 * - Given repeated SAF selection, when activation rotates the capability token, then the stable
 *   workspace ID supplied to native remains unchanged while activation and projection revisions
 *   advance together.
 * - Given a new SAF projection, when activation runs, then candidate authority commits Verified
 *   without a Kotlin StoreHandle rescan.
 * - Given an armed StoreHandle projection failure, when SAF activates, then mount still succeeds
 *   because Kotlin does not rescan that second SQLite.
 * - Given candidate opens as ReadOnlyRecovery, when activate runs, then previous engine stays and
 *   the soft failure is thrown without installing Recovery as success.
 * - Given a soft-failed candidate whose close also throws, when activate runs, then the structured
 *   activation failure still surfaces and the candidate capability is revoked.
 * - Given the previous engine refuses to close, when a Ready candidate is promoted, then neither
 *   engine stays published, both capabilities are revoked and readiness freezes in Recovery.
 * - Given the active engine refuses to close, when the session closes, then the capability is still
 *   revoked and terminal ShuttingDown readiness is still published.
 * - Given persisted Direct root that is not a directory, when cold restore runs, then bind fails
 *   closed with the registry code, the missing path is not created, no native engine opens, and
 *   resnapshot cannot overwrite Recovery with Awaiting.
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
 * - Given a Ready workspace, when another workspace activates, then every published mount is a
 *   complete fact: Ready never appears with a null authority, and freshness is Verified or
 *   Revalidating at that authority's revision.
 * - Given a verified workspace, when another activate starts, then Revalidating is published before
 *   the new Verified. If that activate fails, Verified of the previous authority is restored.
 *
 * Observable outcomes: readiness transitions, capability revocation, published workspace
 *   authority, and structured recovery error fields.
 *
 * TDD proof: RED on 2026-09-16 because workspace switch published Ready with null authority
 * (`WorkspaceMount(readiness=Ready, location=null, authority=null, freshness=Unavailable)`) before
 * the committed location/authority were installed.
 *
 * workspace port identity, media promote calls, and engine-open count.
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
 * TDD proof: RED on 2026-08-09 because every SAF mutation enumerated the whole workspace before
 * and/or after the write, so an eventually consistent DocumentsProvider produced false absence.
 * TDD proof: RED on 2026-08-09 because SAF delete removed active Markdown and stored trash only in
 * app-private projection state, so restart lost the deletion or resurrected the memo.
 * TDD proof: RED on 2026-08-16 because explicit SAF activation synchronously rebuilt the full
 * projection before authority commit, so a blocked provider scan prevented the root from switching.
 * TDD proof: RED on 2026-09-01 because SAF memo mutations with pendingPromotes were rejected with
 * IllegalArgumentException instead of executing the platform media transaction.
 * TDD proof: RED on 2026-09-12 because StoreInvalidationBus stayed at the previous workspace
 * high-water after activation, so a lower-water library's commits were silently dropped.
 * TDD proof: RED on 2026-09-12 because construction opened a bootstrap engine on the calling
 * thread before the startup coroutine ran, and a persisted workspace paid for that bootstrap
 * plus the workspace engine.
 * Excludes: live BoltFFI LomoEngine.open and Compose recovery UI.
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
 * - Reason category: Direct root capability bind.
 * - Old behavior/assertion being replaced: missing Direct path was constructed as a workspace
 *   selection and native open was expected to refuse it with workspace_open_failed.
 * - Why old assertion is no longer correct: Direct bind fails closed when the root is not an
 *   existing directory, without creating the path or opening native.
 * - Coverage preserved by: Recovery still freezes across resnapshot; missing path is still absent.
 * - Why this is not fitting the test to the implementation: the product contract is that an
 *   unbound Direct root never becomes a session capability.
 * - Activate-open-failure fixture: `/tmp/candidate-root` is no longer a valid Direct bind; the
 *   test now uses an existing directory so the failure under test remains native open, not registry
 *   bind.
 * - Reason category: T12 session-owned projection; Kotlin StoreHandle rescan sink deleted.
 * - Old behavior/assertion being replaced: safProjectionRebuildCount / projectionEvents prove
 *   activation did not stream a second SQLite rebuild.
 * - Why old assertion is no longer correct: begin/append/finish SAF rebuild no longer exists on
 *   the native port; session_rebuild_projection is the remaining rebuild path.
 * - Coverage preserved by: Ready + Verified authority on SAF activate, generation advance, and
 *   native engine_open_does_not_materialize_a_second_sqlite.
 * - Why this is not fitting the test to the implementation: product-visible mount still succeeds
 *   without a Kotlin StoreHandle rescan.
 */

import com.lomo.data.testing.DataFunSpec
import com.lomo.data.engine.lan.LanBindCandidate
import com.lomo.data.engine.lan.LanChunkSend
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
import com.lomo.data.engine.lan.LanReceivedBatchDrive
import com.lomo.data.engine.lan.LanReceivedItemRecovery
import com.lomo.data.engine.lan.LanRuntimeInbox
import com.lomo.data.engine.lan.LanInboxWait
import com.lomo.data.engine.lan.LanServicePhase
import com.lomo.data.engine.lan.LanServiceState
import com.lomo.data.engine.lan.LanSendItemPlan
import com.lomo.data.engine.lan.LanSessionChallenge
import com.lomo.data.engine.lan.LanSessionPhase
import com.lomo.data.engine.lan.LanSessionState
import com.lomo.data.engine.lan.LanTransferShape
import com.lomo.data.engine.lan.LanProtocolLimits
import com.lomo.data.engine.store.StoreInvalidationScope
import com.lomo.data.engine.store.StoreMemoCommit
import com.lomo.data.repository.StoreInvalidationBus
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.domain.model.StorageTimestampFormats
import com.lomo.domain.model.WorkspaceMount
import com.lomo.domain.model.WorkspaceProcessDuty
import com.lomo.domain.model.MemoDocumentMutation
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.MarkdownWorkspaceRepository
import com.lomo.domain.model.EngineCommandFailureException
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineRetryDisposition
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
                            invalidation = com.lomo.data.repository.StoreInvalidationBus(),
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { testRustEngineAdapter(port) },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { false },
                        )

                    session.requestEngineStart()

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

                    val mutation = updated.shouldBeInstanceOf<MemoDocumentMutation>()
                    mutation.facts.content shouldBe "- [x] task"
                    mutation.expectedRevision shouldBe 1L
                    mutation.expectedFingerprint shouldBe "a".repeat(64)
                    mutation.facts.memoId shouldBe "2026-07-20_10:00:00_0"
                    mutation.facts.fileFingerprint shouldBe "b".repeat(64)
                    mutation.facts.hasTodo shouldBe true
                    port.lastDocumentCommand shouldBe
                        WorkspaceNativeCommandSpec.ToggleTask(
                            identity = "2026-07-20_10:00:00_0",
                            bodyStart = 2uL,
                            bodyEnd = 5uL,
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
                                category = "validation",
                                code = "stale_snapshot",
                                retryDisposition = "never",
                                diagnostic = "document changed",
                            ),
                        )
                    val session = readySession(filesDir, port, testScheduler)

                    val error =
                        shouldThrow<EngineCommandFailureException> {
                            session.toggleTask(
                                memoIdentity = "2026-07-20_10:00:00_0",
                                actionSpan = MarkdownSourceSpan(startByte = 2uL, endByte = 5uL),
                            )
                        }

                    error.failure.code shouldBe "stale_snapshot"
                    error.failure.category shouldBe EngineFailureCategory.VALIDATION
                    error.failure.retryDisposition shouldBe EngineRetryDisposition.NEVER
                    error.message.orEmpty() shouldContain "document changed"
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
                    val mutation = updated.shouldBeInstanceOf<MemoDocumentMutation>()
                    mutation.facts.content shouldBe "done"
                    mutation.expectedRevision shouldBe 1L
                    mutation.expectedFingerprint shouldBe "a".repeat(64)
                    mutation.facts.memoId shouldBe "2026-07-20_10:00:00_0"
                    mutation.facts.reminders shouldBe emptyList()
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
                            invalidation = com.lomo.data.repository.StoreInvalidationBus(),
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

                    session.requestEngineStart()

                    session.readiness.value shouldBe EngineReadiness.AwaitingWorkspaceSelection
                    opens.single().workspace shouldBe null
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                }
            }
        }

        test("given an owned process when the session is constructed then native opens only after an explicit start request") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-opening").toFile()
                try {
                    val opens = mutableListOf<NativeEngineOpenRequest>()
                    val session =
                        ManagedEngineSession(
                            invalidation = com.lomo.data.repository.StoreInvalidationBus(),
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { request ->
                                opens += request
                                testRustEngineAdapter(
                                    SessionFakeNativeEnginePort(NativeEngineSnapshot.AwaitingWorkspaceSelection),
                                )
                            },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(StandardTestDispatcher(testScheduler)),
                            isContentUri = { false },
                        )

                    session.readiness.value shouldBe EngineReadiness.Opening
                    opens shouldBe emptyList()

                    advanceUntilIdle()

                    // Construction and graph resolution alone never mount the vault.
                    session.readiness.value shouldBe EngineReadiness.Opening
                    opens shouldBe emptyList()

                    session.requestEngineStart() shouldBe EngineReadiness.AwaitingWorkspaceSelection
                    opens.single().workspace shouldBe null
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                }
            }
        }

        test("given repeated explicit start requests then the engine opens exactly once") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-start-once").toFile()
                try {
                    val opens = mutableListOf<NativeEngineOpenRequest>()
                    val session =
                        ManagedEngineSession(
                            invalidation = com.lomo.data.repository.StoreInvalidationBus(),
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { request ->
                                opens += request
                                testRustEngineAdapter(
                                    SessionFakeNativeEnginePort(NativeEngineSnapshot.AwaitingWorkspaceSelection),
                                )
                            },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(StandardTestDispatcher(testScheduler)),
                            isContentUri = { false },
                        )

                    session.requestEngineStart() shouldBe EngineReadiness.AwaitingWorkspaceSelection
                    session.requestEngineStart() shouldBe EngineReadiness.AwaitingWorkspaceSelection
                    advanceUntilIdle()

                    opens.size shouldBe 1
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                }
            }
        }

        test("given a projection-only process when the session is constructed then no native engine is opened") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-projection-only").toFile()
                try {
                    val opens = mutableListOf<NativeEngineOpenRequest>()
                    val session =
                        ManagedEngineSession(
                            invalidation = com.lomo.data.repository.StoreInvalidationBus(),
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { request ->
                                opens += request
                                testRustEngineAdapter(
                                    SessionFakeNativeEnginePort(NativeEngineSnapshot.AwaitingWorkspaceSelection),
                                )
                            },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(StandardTestDispatcher(testScheduler)),
                            isContentUri = { false },
                            ownsNativeEngine = WorkspaceProcessDuty.PROJECTION_ONLY,
                        )

                    session.requestEngineStart()

                    advanceUntilIdle()

                    session.readiness.value shouldBe EngineReadiness.Opening
                    opens shouldBe emptyList()
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                }
            }
        }

        test("given a persisted Direct root when cold restore completes then bootstrap is never opened") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-one-engine").toFile()
                val workspace = kotlin.io.path.createTempDirectory("ws-one-engine").toFile()
                try {
                    val settings = InMemoryDirectorySettingsRepository()
                    settings.setLocation(StorageArea.ROOT, StorageLocation(workspace.absolutePath))
                    val opens = mutableListOf<NativeEngineOpenRequest>()
                    val registry = CapabilityRegistry()
                    val session =
                        ManagedEngineSession(
                            invalidation = com.lomo.data.repository.StoreInvalidationBus(),
                            filesDir = filesDir,
                            capabilityRegistry = registry,
                            openAdapter = { request ->
                                opens += request
                                val snapshot =
                                    if (request.workspace == null) {
                                        NativeEngineSnapshot.AwaitingWorkspaceSelection
                                    } else {
                                        NativeEngineSnapshot.Ready(coreRevision = 4uL, eventSequence = 6uL)
                                    }
                                testRustEngineAdapter(SessionFakeNativeEnginePort(snapshot))
                            },
                            directorySettingsRepository = settings,
                            appScope = CoroutineScope(StandardTestDispatcher(testScheduler)),
                            isContentUri = { false },
                        )

                    session.readiness.value shouldBe EngineReadiness.Opening
                    opens shouldBe emptyList()

                    session.requestEngineStart() shouldBe EngineReadiness.Ready

                    session.activeWorkspaceLocation.value shouldBe StorageLocation(workspace.absolutePath)
                    val direct = opens.single().workspace.shouldBeInstanceOf<NativeWorkspaceSelection.Direct>()
                    registry
                        .resolve(direct.capabilityToken)
                        .shouldBeInstanceOf<DirectCapabilityGrant>()
                        .canonicalRoot shouldBe workspace.canonicalFile
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                    workspace.deleteRecursively()
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
                            invalidation = com.lomo.data.repository.StoreInvalidationBus(),
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { testRustEngineAdapter(port) },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { false },
                        )

                    session.requestEngineStart()

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
                            invalidation = com.lomo.data.repository.StoreInvalidationBus(),
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { testRustEngineAdapter(port) },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { false },
                        )

                    session.requestEngineStart()

                    val challenge = session.beginLanSession("a".repeat(64), 1_000, 60_000)

                    challenge shouldBe port.sessionChallenge
                    port.lanSessionBegins shouldBe 1
                    session.awaitLanInbox(lastGeneration = 0uL, timeoutMs = 0uL).inbox shouldBe
                        port.runtimeInbox
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
                            invalidation = com.lomo.data.repository.StoreInvalidationBus(),
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { testRustEngineAdapter(port) },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { false },
                        )

                    session.requestEngineStart()
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
                    session.lanUnconfirmedBatchChunks("batch-managed", 0u, 65_535u) shouldBe
                        listOf(0u)
                    session.sendLanBatchChunks(
                        listOf(
                            LanChunkSend(
                                "b".repeat(32),
                                "batch-managed",
                                0u,
                                65_535u,
                                0u,
                                byteArrayOf(1, 2, 3, 4),
                            ),
                        ),
                    )
                    port.sentLanChunk shouldBe byteArrayOf(1, 2, 3, 4)
                    session.commitReceivedLanItem("batch-managed", 0u, 1_500).memoId shouldBe "memo-received"
                    port.committedLanBatchId shouldBe "batch-managed"
                    port.committedLanItemIndex shouldBe 0u
                    session.rejectLanBatch("b".repeat(32), "batch-managed", 2_000)
                    port.rejectedLanBatchId shouldBe "batch-managed"
                    session.readiness.value shouldBe
                        EngineReadiness.Ready
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
                            invalidation = com.lomo.data.repository.StoreInvalidationBus(),
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { error("native library unavailable") },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { false },
                        )

                    session.requestEngineStart()

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
                            invalidation = com.lomo.data.repository.StoreInvalidationBus(),
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { throw nativeFailure },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { false },
                        )

                    session.requestEngineStart()

                    val recovery =
                        session.readiness.value.shouldBeInstanceOf<EngineReadiness.ReadOnlyRecovery>()
                    recovery.category shouldBe EngineFailureCategory.PERMISSION
                    recovery.code shouldBe "saf_grant_revoked"
                    recovery.retryDisposition shouldBe EngineRetryDisposition.AFTER_USER_ACTION
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
                            invalidation = com.lomo.data.repository.StoreInvalidationBus(),
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

                    session.requestEngineStart()
                    advanceUntilIdle()
                    session.readiness.value
                        .shouldBeInstanceOf<EngineReadiness.ReadOnlyRecovery>()
                        .code shouldBe "sqlite_integrity_failed"

                    val result = session.rebuildDerivedIndex()

                    result.memosIndexed shouldBe 2uL
                    workspacePorts.sumOf { it.rebuildCount } shouldBe 1
                    session.readiness.value shouldBe
                        EngineReadiness.Ready
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
                            invalidation = com.lomo.data.repository.StoreInvalidationBus(),
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

                    session.requestEngineStart()

                    session.activateWorkspace(StorageLocation(workspace.absolutePath))

                    session.readiness.value shouldBe
                        EngineReadiness.Ready
                    // Bootstrap adapter closed after activate installed the candidate.
                    closedPorts.first().portCloseCount shouldBe 1
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                    workspace.deleteRecursively()
                }
            }
        }

        test("given a high-water publication when a lower-water workspace activates then the next commit publishes") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-reanchor").toFile()
                val firstRoot = kotlin.io.path.createTempDirectory("ws-high").toFile()
                val secondRoot = kotlin.io.path.createTempDirectory("ws-low").toFile()
                try {
                    val bus = StoreInvalidationBus()
                    bus.publish(
                        StoreMemoCommit(
                            operationId = "op-high",
                            memoId = "memo-high",
                            coreRevision = 10,
                            eventSequence = 10,
                            contentRevision = 10,
                            fileFingerprint = "fp",
                            scopes = listOf(StoreInvalidationScope.MemoList),
                            idempotentReplay = false,
                        ),
                    )
                    var remainingHighWater = 10uL
                    val session =
                        ManagedEngineSession(
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { request ->
                                val snapshot =
                                    if (request.workspace == null) {
                                        NativeEngineSnapshot.AwaitingWorkspaceSelection
                                    } else {
                                        NativeEngineSnapshot.Ready(coreRevision = 0uL, eventSequence = 1uL)
                                    }
                                val port =
                                    SessionFakeNativeEnginePort(snapshot).apply {
                                        if (request.workspace != null) {
                                            projectionHighWaterRevision = remainingHighWater
                                            remainingHighWater = 3uL
                                        }
                                    }
                                testRustEngineAdapter(port, invalidation = bus)
                            },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { false },
                            invalidation = bus,
                        )

                    session.requestEngineStart()

                    session.activateWorkspace(StorageLocation(firstRoot.absolutePath))
                    session.activateWorkspace(StorageLocation(secondRoot.absolutePath))
                    bus.publish(
                        StoreMemoCommit(
                            operationId = "op-low",
                            memoId = "memo-low",
                            coreRevision = 4,
                            eventSequence = 4,
                            contentRevision = 1,
                            fileFingerprint = "fp-low",
                            scopes = listOf(StoreInvalidationScope.MemoList),
                            idempotentReplay = false,
                        ),
                    )

                    bus.publications.value.coreRevision shouldBe 4
                    bus.publications.value.eventSequence shouldBe 4
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                    firstRoot.deleteRecursively()
                    secondRoot.deleteRecursively()
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
                            invalidation = com.lomo.data.repository.StoreInvalidationBus(),
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

                    session.requestEngineStart()
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
                val candidate = kotlin.io.path.createTempDirectory("managed-engine-fail-candidate").toFile()
                try {
                    var openCount = 0
                    val session =
                        ManagedEngineSession(
                            invalidation = com.lomo.data.repository.StoreInvalidationBus(),
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

                    session.requestEngineStart()

                    val error =
                        shouldThrow<IllegalStateException> {
                            session.activateWorkspace(StorageLocation(candidate.absolutePath))
                        }
                    error.message shouldBe "native open refused"
                    session.readiness.value shouldBe EngineReadiness.AwaitingWorkspaceSelection
                    openCount shouldBe 2 // bootstrap + failed candidate
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                    candidate.deleteRecursively()
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
                            invalidation = com.lomo.data.repository.StoreInvalidationBus(),
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

                    session.requestEngineStart()

                    val treeUri = "content://com.lomo.documents/tree/primary%3ALomo"
                    session.activateWorkspace(StorageLocation(treeUri))

                    val saf = observed.shouldBeInstanceOf<NativeWorkspaceSelection.Saf>()
                    registry.resolve(saf.capabilityToken).shouldBeInstanceOf<SafCapabilityGrant>().treeUri shouldBe treeUri
                    saf.stableWorkspaceId shouldBe SafWorkspaceIdentity.fromTreeUri(treeUri)
                    session.readiness.value shouldBe
                        EngineReadiness.Ready
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                }
            }
        }

        test("given an empty SAF projection when activation completes then authority is verified without a second scan") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-saf-slow-switch").toFile()
                val tree = StorageLocation("content://com.lomo.documents/tree/primary%3ASlow")
                val settings = InMemoryDirectorySettingsRepository()
                val candidate =
                    SessionFakeNativeEnginePort(
                        NativeEngineSnapshot.Ready(coreRevision = 1uL, eventSequence = 1uL),
                    ).apply {
                        projectionHighWaterRevision = 0uL
                    }
                val session =
                    ManagedEngineSession(
                        invalidation = com.lomo.data.repository.StoreInvalidationBus(),
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

                session.requestEngineStart()
                try {
                    session.activateWorkspace(tree)

                    session.activeWorkspaceLocation.value shouldBe tree
                    session.workspaceAuthority.value?.workspaceId shouldBe SafWorkspaceIdentity.fromTreeUri(tree.raw).value
                    session.projectionFreshness.value shouldBe ProjectionFreshness.Verified(0uL)
                } finally {
                    session.close()
                    filesDir.deleteRecursively()
                }
            }
        }

        test("given an active SAF workspace when another workspace activates then the next generation is not blocked") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-saf-next-switch").toFile()
                val nextRoot = kotlin.io.path.createTempDirectory("managed-engine-next-root").toFile()
                val firstCandidate =
                    SessionFakeNativeEnginePort(NativeEngineSnapshot.Ready(1uL, 1uL)).apply {
                    }
                var openCount = 0
                val session =
                    ManagedEngineSession(
                        invalidation = com.lomo.data.repository.StoreInvalidationBus(),
                        filesDir = filesDir,
                        capabilityRegistry = CapabilityRegistry(),
                        openAdapter = { _ ->
                            openCount += 1
                            testRustEngineAdapter(
                                when (openCount) {
                                    1 -> SessionFakeNativeEnginePort(NativeEngineSnapshot.AwaitingWorkspaceSelection)
                                    2 -> firstCandidate
                                    else -> SessionFakeNativeEnginePort(NativeEngineSnapshot.Ready(2uL, 2uL))
                                },
                            )
                        },
                        directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                        appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                        isContentUri = { it.startsWith("content://") },
                    )

                session.requestEngineStart()
                try {
                    session.activateWorkspace(
                        StorageLocation("content://com.lomo.documents/tree/primary%3ABlocked"),
                    )
                    session.activateWorkspace(StorageLocation(nextRoot.absolutePath))
                    session.activeWorkspaceLocation.value shouldBe StorageLocation(nextRoot.absolutePath)
                    firstCandidate.portCloseCount shouldBe 1
                } finally {
                    session.close()
                    filesDir.deleteRecursively()
                    nextRoot.deleteRecursively()
                }
            }
        }

        test("given a Ready workspace when another activates then mount never publishes mixed Ready facts") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-atomic-mount").toFile()
                val firstRoot = kotlin.io.path.createTempDirectory("managed-engine-first-root").toFile()
                val nextRoot = kotlin.io.path.createTempDirectory("managed-engine-next-root").toFile()
                var openCount = 0
                val session =
                    ManagedEngineSession(
                        invalidation = com.lomo.data.repository.StoreInvalidationBus(),
                        filesDir = filesDir,
                        capabilityRegistry = CapabilityRegistry(),
                        openAdapter = { _ ->
                            openCount += 1
                            testRustEngineAdapter(
                                when (openCount) {
                                    1 -> SessionFakeNativeEnginePort(NativeEngineSnapshot.AwaitingWorkspaceSelection)
                                    2 -> SessionFakeNativeEnginePort(NativeEngineSnapshot.Ready(1uL, 1uL))
                                    else -> SessionFakeNativeEnginePort(NativeEngineSnapshot.Ready(2uL, 2uL))
                                },
                            )
                        },
                        directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                        appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                        isContentUri = { it.startsWith("content://") },
                    )

                session.requestEngineStart()
                val mounts = mutableListOf<WorkspaceMount>()
                val collector =
                    launch(UnconfinedTestDispatcher(testScheduler)) {
                        session.mount.collect { mounts += it }
                    }
                try {
                    session.activateWorkspace(StorageLocation(firstRoot.absolutePath))
                    session.activateWorkspace(StorageLocation(nextRoot.absolutePath))
                    collector.cancel()
                    check(mounts.any { mount -> mount.readiness is EngineReadiness.Ready }) {
                        "Collector observed no Ready mount; publication was not collected"
                    }

                    mounts
                        .filter { mount -> mount.readiness is EngineReadiness.Ready }
                        .forEach { mount ->
                            val authority =
                                checkNotNull(mount.authority) {
                                    "Ready mount published without workspace authority: $mount"
                                }
                            checkNotNull(mount.location) {
                                "Ready mount published without workspace location: $mount"
                            }
                            when (val freshness = mount.freshness) {
                                is ProjectionFreshness.Verified ->
                                    freshness.revision shouldBe authority.projectionRevision
                                is ProjectionFreshness.Revalidating ->
                                    freshness.lastVerifiedRevision shouldBe authority.projectionRevision
                                ProjectionFreshness.Unavailable ->
                                    error("Ready mount published unreadable freshness: $mount")
                            }
                        }
                } finally {
                    collector.cancel()
                    session.close()
                    filesDir.deleteRecursively()
                    firstRoot.deleteRecursively()
                    nextRoot.deleteRecursively()
                }
            }
        }

        test("given a verified workspace when another activates then Revalidating is published before the new Verified") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-revalidating").toFile()
                val firstRoot = kotlin.io.path.createTempDirectory("managed-engine-revalidating-first").toFile()
                val nextRoot = kotlin.io.path.createTempDirectory("managed-engine-revalidating-next").toFile()
                var openCount = 0
                val session =
                    ManagedEngineSession(
                        invalidation = com.lomo.data.repository.StoreInvalidationBus(),
                        filesDir = filesDir,
                        capabilityRegistry = CapabilityRegistry(),
                        openAdapter = { _ ->
                            openCount += 1
                            testRustEngineAdapter(
                                when (openCount) {
                                    1 -> SessionFakeNativeEnginePort(NativeEngineSnapshot.AwaitingWorkspaceSelection)
                                    2 -> SessionFakeNativeEnginePort(NativeEngineSnapshot.Ready(1uL, 1uL))
                                    else -> SessionFakeNativeEnginePort(NativeEngineSnapshot.Ready(2uL, 2uL))
                                },
                            )
                        },
                        directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                        appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                        isContentUri = { it.startsWith("content://") },
                    )

                session.requestEngineStart()
                val mounts = mutableListOf<WorkspaceMount>()
                val collector =
                    launch(UnconfinedTestDispatcher(testScheduler)) {
                        session.mount.collect { mounts += it }
                    }
                try {
                    session.activateWorkspace(StorageLocation(firstRoot.absolutePath))
                    val firstRevision = checkNotNull(session.workspaceAuthority.value).projectionRevision
                    mounts.clear()
                    session.activateWorkspace(StorageLocation(nextRoot.absolutePath))
                    collector.cancel()
                    val revalidating =
                        mounts.map { mount -> mount.freshness }.filterIsInstance<ProjectionFreshness.Revalidating>()
                    revalidating.any { freshness -> freshness.lastVerifiedRevision == firstRevision } shouldBe true
                    session.projectionFreshness.value.shouldBeInstanceOf<ProjectionFreshness.Verified>()
                } finally {
                    collector.cancel()
                    session.close()
                    filesDir.deleteRecursively()
                    firstRoot.deleteRecursively()
                    nextRoot.deleteRecursively()
                }
            }
        }

        test("given a verified workspace when the next activate fails then Verified is restored") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-restore-verified").toFile()
                val firstRoot = kotlin.io.path.createTempDirectory("managed-engine-restore-first").toFile()
                val nextRoot = kotlin.io.path.createTempDirectory("managed-engine-restore-next").toFile()
                var openCount = 0
                val session =
                    ManagedEngineSession(
                        invalidation = com.lomo.data.repository.StoreInvalidationBus(),
                        filesDir = filesDir,
                        capabilityRegistry = CapabilityRegistry(),
                        openAdapter = { request ->
                            openCount += 1
                            if (openCount >= 3 && request.workspace != null) {
                                error("native open refused")
                            }
                            testRustEngineAdapter(
                                if (request.workspace == null) {
                                    SessionFakeNativeEnginePort(NativeEngineSnapshot.AwaitingWorkspaceSelection)
                                } else {
                                    SessionFakeNativeEnginePort(NativeEngineSnapshot.Ready(1uL, 1uL))
                                },
                            )
                        },
                        directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                        appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                        isContentUri = { false },
                    )

                session.requestEngineStart()
                try {
                    session.activateWorkspace(StorageLocation(firstRoot.absolutePath))
                    val firstAuthority = checkNotNull(session.workspaceAuthority.value)
                    session.projectionFreshness.value.shouldBeInstanceOf<ProjectionFreshness.Verified>()
                    shouldThrow<IllegalStateException> {
                        session.activateWorkspace(StorageLocation(nextRoot.absolutePath))
                    }
                    session.readiness.value shouldBe EngineReadiness.Ready
                    session.workspaceAuthority.value shouldBe firstAuthority
                    session.projectionFreshness.value shouldBe
                        ProjectionFreshness.Verified(firstAuthority.projectionRevision)
                } finally {
                    session.close()
                    filesDir.deleteRecursively()
                    firstRoot.deleteRecursively()
                    nextRoot.deleteRecursively()
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
                            invalidation = com.lomo.data.repository.StoreInvalidationBus(),
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
                                    if (request.workspace != null) {
                                        projectionRevision += 1uL
                                        this.projectionHighWaterRevision = projectionRevision
                                    }
                                }.let(::testRustEngineAdapter)
                            },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { it.startsWith("content://") },
                        )

                    session.requestEngineStart()

                    val tree =
                        StorageLocation("content://com.lomo.documents/tree/primary%3ALomo")
                    session.activateWorkspace(tree)
                    session.projectionFreshness.value shouldBe ProjectionFreshness.Verified(1uL)
                    val firstAuthority = checkNotNull(session.workspaceAuthority.value)
                    session.activateWorkspace(tree)
                    session.projectionFreshness.value shouldBe ProjectionFreshness.Verified(2uL)
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

        test("given an armed StoreHandle projection failure when SAF activates then mount stays verified") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-saf-projection-fail").toFile()
                val previousRoot = kotlin.io.path.createTempDirectory("ws-saf-projection-previous").toFile()
                try {
                    val registry = CapabilityRegistry()
                    val ports = mutableListOf<SessionFakeNativeEnginePort>()
                    var safToken: String? = null
                    val session =
                        ManagedEngineSession(
                            invalidation = com.lomo.data.repository.StoreInvalidationBus(),
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
                                            )
                                        }
                                    }
                                ports += port
                                testRustEngineAdapter(port)
                            },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { it.startsWith("content://") },
                        )

                    session.requestEngineStart()
                    session.activateWorkspace(StorageLocation(previousRoot.absolutePath))
                    val previousAuthority = checkNotNull(session.workspaceAuthority.value)

                    session.activateWorkspace(
                        StorageLocation("content://com.lomo.documents/tree/primary%3ALomo"),
                    )
                    session.projectionFreshness.value shouldBe ProjectionFreshness.Verified(0uL)
                    session.readiness.value shouldBe
                        EngineReadiness.Ready
                    val safAuthority = checkNotNull(session.workspaceAuthority.value)
                    safAuthority.generation shouldBe previousAuthority.generation + 1
                    safAuthority.workspaceId shouldBe SafWorkspaceIdentity.fromTreeUri(
                        "content://com.lomo.documents/tree/primary%3ALomo",
                    ).value
                    safAuthority.projectionRevision shouldBe 0uL
                    ports[1].portCloseCount shouldBe 1
                    ports.last().portCloseCount shouldBe 0
                    registry.resolve(checkNotNull(safToken)).shouldBeInstanceOf<SafCapabilityGrant>().treeUri shouldBe
                        "content://com.lomo.documents/tree/primary%3ALomo"
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
                            invalidation = com.lomo.data.repository.StoreInvalidationBus(),
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

                    session.requestEngineStart()

                    // Unconfined dispatcher runs cold-restore launch immediately.
                    session.readiness.value shouldBe
                        EngineReadiness.Ready
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

        test("given durable SAF projection when cold restore completes then Ready is verified without a second scan") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-saf-cold-refresh").toFile()
                val tree = StorageLocation("content://com.lomo.documents/tree/primary%3ALomo")
                val settings = InMemoryDirectorySettingsRepository()
                settings.setLocation(StorageArea.ROOT, tree)
                val candidate =
                    SessionFakeNativeEnginePort(
                        NativeEngineSnapshot.Ready(coreRevision = 5uL, eventSequence = 8uL),
                    ).apply {
                        projectionHighWaterRevision = 41uL
                    }
                val session =
                    ManagedEngineSession(
                        invalidation = com.lomo.data.repository.StoreInvalidationBus(),
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

                session.requestEngineStart()
                try {
                    session.readiness.value shouldBe
                        EngineReadiness.Ready
                    session.activeWorkspaceLocation.value shouldBe tree
                    checkNotNull(session.workspaceAuthority.value).projectionRevision shouldBe 41uL
                    session.projectionFreshness.value shouldBe ProjectionFreshness.Verified(41uL)
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
                        }
                    }
                val session =
                    ManagedEngineSession(
                        invalidation = com.lomo.data.repository.StoreInvalidationBus(),
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

                session.requestEngineStart()
                try {
                    val recovery =
                        session.readiness.value.shouldBeInstanceOf<EngineReadiness.ReadOnlyRecovery>()
                    recovery.code shouldBe "saf_grant_revoked"
                    recovery.category shouldBe EngineFailureCategory.PERMISSION
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
                            invalidation = com.lomo.data.repository.StoreInvalidationBus(),
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

                    session.requestEngineStart()

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
                            invalidation = com.lomo.data.repository.StoreInvalidationBus(),
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

                    session.requestEngineStart()
                    session.activateWorkspace(StorageLocation(previousRoot.absolutePath))
                    session.readiness.value shouldBe
                        EngineReadiness.Ready
                    val readyPortCloseBefore = closedPorts.last().portCloseCount

                    val error =
                        shouldThrow<WorkspaceActivationException> {
                            session.activateWorkspace(StorageLocation(candidateRoot.absolutePath))
                        }
                    error.recovery.code shouldBe "saf_grant_revoked"
                    session.readiness.value shouldBe
                        EngineReadiness.Ready
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
                            invalidation = com.lomo.data.repository.StoreInvalidationBus(),
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

                    session.requestEngineStart()

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
                            invalidation = com.lomo.data.repository.StoreInvalidationBus(),
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

                    session.requestEngineStart()
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
                            invalidation = com.lomo.data.repository.StoreInvalidationBus(),
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

                    session.requestEngineStart()
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

        test("given missing Direct root when cold restore runs then bind fails closed without native open") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-cold-fail").toFile()
                val parent = kotlin.io.path.createTempDirectory("managed-engine-missing-parent").toFile()
                val missing = java.io.File(parent, "nested-workspace")
                try {
                    val settings = InMemoryDirectorySettingsRepository()
                    settings.setLocation(StorageArea.ROOT, StorageLocation(missing.absolutePath))
                    val opens = mutableListOf<NativeEngineOpenRequest>()
                    val session =
                        ManagedEngineSession(
                            invalidation = com.lomo.data.repository.StoreInvalidationBus(),
                            filesDir = filesDir,
                            capabilityRegistry = CapabilityRegistry(),
                            openAdapter = { request ->
                                opens += request
                                error("native open must not run for an unbound Direct root")
                            },
                            directorySettingsRepository = settings,
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { false },
                        )

                    session.requestEngineStart()

                    val recovery = session.readiness.value.shouldBeInstanceOf<EngineReadiness.ReadOnlyRecovery>()
                    recovery.category shouldBe EngineFailureCategory.VALIDATION
                    recovery.code shouldBe "workspace_root_not_directory"
                    recovery.diagnostic.shouldContain("direct workspace root must be a directory")
                    opens shouldBe emptyList()
                    missing.exists() shouldBe false

                    session.resnapshot()
                    session.readiness.value.shouldBeInstanceOf<EngineReadiness.ReadOnlyRecovery>().code shouldBe
                        "workspace_root_not_directory"
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                    parent.deleteRecursively()
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
                        drive = LanReceivedBatchDrive.ReadyToCommit,
                        confirmedBytes = 0uL,
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

    override fun lanTransferShape(): LanTransferShape =
        LanTransferShape(bodySlot = 0u, chunkPlaintextBytes = 0u, maxInflightChunks = 4u)

    override fun lanProtocolLimits(): LanProtocolLimits =
        LanProtocolLimits(
            protocolVersion = 3u,
            pairingTtlMs = 120_000L,
            sessionTtlMs = 60_000L,
            approvalTtlMs = 900_000L,
        )

    override fun configureLanIdentity(identity: LanDeviceIdentity): LanLocalIdentity =
        LanLocalIdentity(deviceId = "c".repeat(64), displayName = identity.displayName)

    override fun beginLanPairing(
        peerDeviceId: String,
        nowMs: Long,
        ttlMs: Long,
    ): LanPairingChallenge = error("pairing not expected")

    override fun awaitLanInbox(lastGeneration: ULong, timeoutMs: ULong): LanInboxWait =
        LanInboxWait(
            generation = lastGeneration,
            inbox = runtimeInbox,
            rejectedConnectionCount = 0uL,
            lastRejectionDiagnostic = null,
        )

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

    override fun sendLanBatchChunks(chunks: List<LanChunkSend>) {
        sentLanChunk = chunks.lastOrNull()?.plaintext
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
    ): com.lomo.nativebridge.StoreMemoCommit {
        committedLanBatchId = batchId
        committedLanItemIndex = itemIndex
        return nativeLanCommit()
    }

    override fun failReceivedLanItem(
        batchId: String,
        itemIndex: UInt,
        code: String,
    ) = Unit

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

    override fun queryMediaManifest(
        workspaceRoot: String,
        verifiedEntries: List<com.lomo.nativebridge.MediaCommittedEntryDto>,
    ): com.lomo.nativebridge.MediaManifestDto =
        com.lomo.nativebridge.MediaManifestDto(stageDirName = "stage", entries = emptyList())

    override fun sessionMediaOrphanSweep(
        nowMs: ULong?,
        recoveryWindowMs: ULong,
        externalDrafts: List<com.lomo.nativebridge.SessionDraftGuardDto>,
    ): com.lomo.nativebridge.SessionMediaSweepReportDto =
        com.lomo.nativebridge.SessionMediaSweepReportDto(
            candidates = 0uL,
            protections = emptyList(),
            movedToTrash = emptyList(),
            permanentlyDeletedDigests = emptyList(),
            keptLive = 0uL,
            failures = emptyList(),
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

    override fun sessionImportArchive(
        workspaceRoot: String,
        archivePath: String,
        stagingRoot: String,
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
    var portCloseCount: Int = 0
    var closeFailure: Throwable? = null
    var renderCallCount: Int = 0
    val workspaceCalls = mutableListOf<String>()
    var documentTerminal: NativeJobStep = NativeJobStep.Completed
    var lastDocumentCommand: WorkspaceNativeCommandSpec? = null
    val commandEvents = mutableListOf<String>()
    var lastExpectedState: WorkspaceNativeExpectedState? = null
    var lastExpectedFingerprint: String? = null
    var documentResult: WorkspaceNativeCommandResultSnapshot =
        WorkspaceNativeCommandResultSnapshot(
            path = "2026-07-20.md",
            resultFingerprint = "b".repeat(64),
            bytesWritten = 22uL,
            affectedMemo = null,
        )
    val memoSnapshots = mutableMapOf<String, com.lomo.nativebridge.StoreMemoSnapshot>()
    var documentCommandFailure: Throwable? = null
    var rebuildCount: Int = 0
    var onRebuild: (() -> Unit)? = null
    var projectionHighWaterRevision: ULong = 0uL
    var onQueryMemos: (() -> Unit)? = null

    override fun state(): NativeEngineSnapshot = snapshot

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



    override fun startWorkspaceDocumentCommand(
        path: String,
        expectedState: WorkspaceNativeExpectedState,
        command: WorkspaceNativeCommandSpec,
        deadlineMillis: ULong,
    ): String {
        documentCommandFailure?.let { throw it }
        lastExpectedState = expectedState
        lastExpectedFingerprint = (expectedState as? WorkspaceNativeExpectedState.Match)?.fingerprint
        lastDocumentCommand = command
        commandEvents += "document"
        return "document-job"
    }

    override fun readWorkspaceDocumentCommandResult(jobId: String): WorkspaceNativeCommandResultSnapshot =
        documentResult

    override fun queryMemos(
        query: com.lomo.nativebridge.StoreMemoQuery,
        cursor: com.lomo.nativebridge.StorePageCursor?,
        pageSize: UInt,
        startMemoId: String?,
        backward: Boolean,
    ): com.lomo.nativebridge.StoreMemoPage {
        onQueryMemos?.also { callback ->
            onQueryMemos = null
            callback()
        }
        return com.lomo.nativebridge.StoreMemoPage(
            items = emptyList(),
            nextCursor = null,
            prevCursor = null,
            itemsBefore = 0uL,
            itemsAfter = 0uL,
            highWaterRevision = projectionHighWaterRevision,
            queryFingerprint = "fake-query",
        )
    }

    override fun queryCount(query: com.lomo.nativebridge.StoreMemoQuery): ULong =
        memoSnapshots.values.count { !it.summary.isTrashed }.toULong()

    override fun sessionReminderPlan(nowUtcMs: Long?): com.lomo.nativebridge.StoreReminderPlan =
        com.lomo.nativebridge.StoreReminderPlan(
            alarms = emptyList(),
            droppedCount = 0u,
            workspaceGeneration = "gen-test",
        )

    override fun getMemo(memoId: String): com.lomo.nativebridge.StoreMemoSnapshot? =
        memoSnapshots[memoId]

    override fun sidebarProjection(): com.lomo.nativebridge.StoreSidebarProjection =
        error("sidebar projection not expected")

    override fun commitWorkspaceDocumentFacts(
        command: com.lomo.nativebridge.StoreMemoCommand,
        projection: com.lomo.nativebridge.StoreSafMemoProjection,
    ): com.lomo.nativebridge.StoreMemoCommit = fakeCommit(command)

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
            rewritten = true,
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
            scopes = listOf(com.lomo.nativebridge.StoreInvalidationScope.MEMO_LIST),
            idempotentReplay = false,
        )

    override fun close() {
        portCloseCount += 1
        closeFailure?.let { throw it }
    }
}

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

private fun testRustEngineAdapter(
    port: SessionFakeNativeEnginePort,
    invalidation: StoreInvalidationBus = StoreInvalidationBus(),
): RustEngineAdapter =
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
        invalidation = invalidation,
    )

private fun nativeLanCommit(
    memoId: String = "memo-received",
): com.lomo.nativebridge.StoreMemoCommit =
    com.lomo.nativebridge.StoreMemoCommit(
        operationId = "lan-$memoId",
        memoId = memoId,
        coreRevision = 1uL,
        eventSequence = 1uL,
        contentRevision = 1uL,
        fileFingerprint = "fp",
        scopes = listOf(com.lomo.nativebridge.StoreInvalidationScope.MEMO_LIST),
        idempotentReplay = false,
    )

private fun storeMemoSnapshot(
    memoId: String,
    body: String,
    hasTodo: Boolean,
    reminders: List<com.lomo.nativebridge.WorkspaceReminderReference> = emptyList(),
): com.lomo.nativebridge.StoreMemoSnapshot =
    com.lomo.nativebridge.StoreMemoSnapshot(
        summary =
            com.lomo.nativebridge.StoreMemoSummary(
                memoId = memoId,
                sourcePath = "2026-07-20.md",
                fileFingerprint = "a".repeat(64),
                updatedAtMs = 1L,
                createdAtMs = 1L,
                hasTodo = hasTodo,
                hasUrl = false,
                hasAttachment = false,
                isPinned = false,
                isTrashed = false,
                bodyPreview = body,
                contentRevision = 1uL,
                rank = null,
                tags = emptyList(),
                imageUrls = emptyList(),
                reminders = reminders,
                isPending = false,
                charCount = body.length.toLong(),
            ),
        body = body,
    )

private fun documentFacts(
    fingerprint: String,
    content: String,
    hasTodo: Boolean = false,
    reminders: List<WorkspaceReminderReferenceSnapshot> = emptyList(),
): WorkspaceDocumentMemoFactsSnapshot =
    WorkspaceDocumentMemoFactsSnapshot(
        path = "2026-07-20.md",
        identity = "2026-07-20_10:00:00_0",
        timePart = "10:00:00",
        fingerprint = fingerprint,
        tags = emptyList(),
        attachments = emptyList(),
        reminders = reminders,
        hasTodo = hasTodo,
        hasUrl = false,
        content = content,
    )

private fun readyTaskPort(): SessionFakeNativeEnginePort =
    SessionFakeNativeEnginePort(
        NativeEngineSnapshot.Ready(coreRevision = 1uL, eventSequence = 1uL),
    ).also { port ->
        port.memoSnapshots["2026-07-20_10:00:00_0"] =
            storeMemoSnapshot(
                memoId = "2026-07-20_10:00:00_0",
                body = "- [ ] task",
                hasTodo = true,
            )
        port.documentResult =
            port.documentResult.copy(
                path = "2026-07-20.md",
                resultFingerprint = "b".repeat(64),
                affectedMemo =
                    documentFacts(
                        fingerprint = "b".repeat(64),
                        content = "- [x] task",
                        hasTodo = true,
                    ),
            )
    }

private fun readyReminderPort(): SessionFakeNativeEnginePort =
    SessionFakeNativeEnginePort(
        NativeEngineSnapshot.Ready(coreRevision = 1uL, eventSequence = 1uL),
    ).also { port ->
        port.memoSnapshots["2026-07-20_10:00:00_0"] =
            storeMemoSnapshot(
                memoId = "2026-07-20_10:00:00_0",
                body = "@2026-07-20-09:30x2",
                hasTodo = false,
                reminders = listOf(reminderSnapshot().toBridgeForTest()),
            )
        port.documentResult =
            port.documentResult.copy(
                path = "2026-07-20.md",
                resultFingerprint = "b".repeat(64),
                affectedMemo =
                    documentFacts(
                        fingerprint = "b".repeat(64),
                        content = "done",
                    ),
            )
    }

private fun WorkspaceReminderReferenceSnapshot.toBridgeForTest(): com.lomo.nativebridge.WorkspaceReminderReference =
    com.lomo.nativebridge.WorkspaceReminderReference(
        opaqueId = opaqueId,
        revision = revision,
        memoIdentity = memoIdentity,
        sourceStart = sourceStart,
        sourceEnd = sourceEnd,
        tokenFingerprint = tokenFingerprint,
        fingerprintOrdinal = fingerprintOrdinal,
        embeddedId = embeddedId,
        token = token,
        dueAtLocal = dueAtLocal,
        repeatCount = repeatCount,
        firedCount = firedCount,
        done = done,
        intervalMinutes = intervalMinutes,
        recurrenceCode = recurrenceCode,
    )

private fun reminderSnapshot(): WorkspaceReminderReferenceSnapshot =
    WorkspaceReminderReferenceSnapshot(
        opaqueId = "reminder-id",
        revision = "a".repeat(64),
        memoIdentity = "2026-07-20_10:00:00_0",
        sourceStart = 11uL,
        sourceEnd = 33uL,
        tokenFingerprint = "c".repeat(64),
        fingerprintOrdinal = 0u,
        embeddedId = null,
        token = "@2026-07-20-09:30x2",
        dueAtLocal = "2026-07-20-09:30",
        repeatCount = 2u,
        firedCount = 0u,
        done = false,
        intervalMinutes = 10u,
        recurrenceCode = "",
    )

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun readySession(
    filesDir: java.io.File,
    port: SessionFakeNativeEnginePort,
    scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
): ManagedEngineSession =
    ManagedEngineSession(
        invalidation = com.lomo.data.repository.StoreInvalidationBus(),
        filesDir = filesDir,
        capabilityRegistry = CapabilityRegistry(),
        openAdapter = { testRustEngineAdapter(port) },
        directorySettingsRepository = InMemoryDirectorySettingsRepository(),
        appScope = CoroutineScope(UnconfinedTestDispatcher(scheduler)),
        isContentUri = { false },
    ).apply {
        requestEngineStart()
    }
