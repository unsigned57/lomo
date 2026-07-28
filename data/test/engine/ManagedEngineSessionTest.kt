package com.lomo.data.engine

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
 * - Given Direct selection, when activate succeeds with Ready, then Ready is published and previous
 *   adapter closes once.
 * - Given open throws, when activate fails, then previous readiness remains and candidate is not
 *   installed.
 * - Given repeated SAF selection, when activation rotates the capability token, then the stable
 *   workspace ID supplied to native remains unchanged.
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
 * - Given an active Ready adapter, when workspace scan start/drive/read routes through the session,
 *   then all calls use that adapter's same native port and no additional engine is opened.
 * - Given an in-flight workspace call, when a Ready candidate is installed, then the session's
 *   exclusive lease waits for the call to release before closing the previous port.
 * - Given the domain render boundary, when it renders through the session, then the same active
 *   adapter and native port serve the request without constructing another engine.
 * - Given a Rust-scanned reminder reference, when queried and rewritten, then typed facts are
 *   mapped without raw parsing and the complete reference is sent through the same session port.
 *
 * Observable outcomes: readiness StateFlow, open request workspace shape, adapter close counts,
 * workspace port identity and engine-open count.
 * TDD proof: RED on 2026-07-27 because NativeWorkspaceSelection.Saf exposed no stableWorkspaceId;
 * repeated activation could only send the newly randomized capability token to native.
 * TDD proof: RED on 2026-07-27 because a throwing engine close skipped capability revoke, terminal
 * readiness and candidate release, and a failed previous retirement still published the candidate.
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
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
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
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.time.LocalDateTime

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
                                testRustEngineAdapter(SessionFakeNativeEnginePort(snapshot))
                            },
                            directorySettingsRepository = InMemoryDirectorySettingsRepository(),
                            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                            isContentUri = { it.startsWith("content://") },
                        )

                    val tree =
                        StorageLocation("content://com.lomo.documents/tree/primary%3ALomo")
                    session.activateWorkspace(tree)
                    session.activateWorkspace(tree)

                    observed.size shouldBe 2
                    observed[0].stableWorkspaceId shouldBe observed[1].stableWorkspaceId
                    (observed[0].capabilityToken == observed[1].capabilityToken) shouldBe false
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                }
            }
        }

        test("given persisted root when session starts then cold restore activates") {
            runTest {
                val filesDir = kotlin.io.path.createTempDirectory("managed-engine-restore").toFile()
                val workspace = kotlin.io.path.createTempDirectory("ws-restore").toFile()
                try {
                    val settings = InMemoryDirectorySettingsRepository()
                    settings.setLocation(StorageArea.ROOT, StorageLocation(workspace.absolutePath))
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
                    session.close()
                } finally {
                    filesDir.deleteRecursively()
                    workspace.deleteRecursively()
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
    }
}

private class InMemoryDirectorySettingsRepository : DirectorySettingsRepository {
    private val locations =
        MutableStateFlow<MutableMap<StorageArea, StorageLocation?>>(mutableMapOf())
    private val displayNames =
        MutableStateFlow<MutableMap<StorageArea, String?>>(mutableMapOf())

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
}

private class SessionFakeNativeEnginePort(
    initialSnapshot: NativeEngineSnapshot,
) : WorkspaceNativeEnginePort {

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
    var documentTerminal: NativeJobStep = NativeJobStep.Completed
    var lastDocumentCommand: WorkspaceNativeCommandSpec? = null
    var lastExpectedFingerprint: String? = null
    private var listener: ((NativeCoreEvent) -> Unit)? = null

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
        workspaceCalls += "read-scan:$jobId"
        return scanPages.removeFirstOrNull()
            ?: WorkspaceScanPageSnapshot(items = emptyList(), nextCursor = null)
    }

    override fun startWorkspaceDocumentCommand(
        path: String,
        expectedFingerprint: String,
        command: WorkspaceNativeCommandSpec,
        deadlineMillis: ULong,
    ): String {
        lastExpectedFingerprint = expectedFingerprint
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
    ): com.lomo.nativebridge.StoreMemoPage = error("store query not expected")

    override fun listHistoryAttachmentRefs(): List<com.lomo.nativebridge.StoreHistoryAttachmentRef> =
        emptyList()

    override fun getMemo(memoId: String): com.lomo.nativebridge.StoreMemoSnapshot? =
        error("store get not expected")

    override fun applyMemoCommand(
        command: com.lomo.nativebridge.StoreMemoCommand,
    ): com.lomo.nativebridge.StoreMemoCommit = error("store apply not expected")

    override fun startRebuild(batchSize: UInt): com.lomo.nativebridge.StoreRebuildResult =
        error("store rebuild not expected")

    override fun close() {
        portCloseCount += 1
        closeFailure?.let { throw it }
    }
}

private data class ScanGate(
    val entered: CountDownLatch,
    val release: CountDownLatch,
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
