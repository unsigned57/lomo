package com.lomo.app.feature.synccenter

/*
 * Behavior Contract:
 * - Unit under test: SyncCenterViewModel over FakeRemoteSyncCenterRepository (P5-10)
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: dark Sync Center host ViewModel loads config/session/conflicts, paginates,
 *   applies expected-revision resolutions, and on conflict select loads markdown/binary detail
 *   facts via domain ports (real bodies when fake repo returns them; binary digests-only;
 *   detail failure fail-closed). No production DI.
 *
 * Scenarios:
 * - Given fake page, when Open dispatched, then Ready load with items and attention count.
 * - Given nextCursor, when LoadMoreConflicts, then items append without dropping prior paths.
 * - Given keep_local on path, when ApplyResolutions, then repository receives expected revision
 *   and applied path is removed from Ready items.
 * - Given repository list throws RemoteSyncCenterFailure, when Open, then Failed load message
 *   preserves category:code.
 * - Given stale resolve failure, when ApplyResolutions, then Ready lastError set and isResolving false.
 * - Given markdown path with non-null bodies from fake repo, when SelectConflict, then
 *   markdownDetailByPath carries real base/local/remote bodies.
 * - Given binary path, when SelectConflict, then binaryDetailByPath has digests/source and no text
 *   preview fields exist on the facts type.
 * - Given markdownConflictFacts throws RemoteSyncCenterFailure, when SelectConflict, then Ready
 *   lastError is category:code, isLoadingDetail false, and markdownDetailByPath stays empty for path.
 *
 * Observable outcomes: uiState load fields + fake repository last request / detail calls.
 * Excludes: Compose, Koin registration, real BoltFFI/JNI.
 */

import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.domain.model.RemoteSyncBackendLabel
import com.lomo.domain.model.RemoteSyncBinaryConflictFacts
import com.lomo.domain.model.RemoteSyncCenterFailure
import com.lomo.domain.model.RemoteSyncConfigSummary
import com.lomo.domain.model.RemoteSyncConflictPage
import com.lomo.domain.model.RemoteSyncConflictPath
import com.lomo.domain.model.RemoteSyncConflictPathStatus
import com.lomo.domain.model.RemoteSyncConflictResolution
import com.lomo.domain.model.RemoteSyncConflictResolveResult
import com.lomo.domain.model.RemoteSyncMarkdownConflictFacts
import com.lomo.domain.model.RemoteSyncSessionPhase
import com.lomo.domain.model.RemoteSyncSessionProgress
import com.lomo.domain.repository.RemoteSyncCenterRepository
import com.lomo.domain.usecase.DispatcherProvider
import com.lomo.domain.usecase.RemoteSyncCenterUseCase
import kotlinx.coroutines.CoroutineDispatcher
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SyncCenterViewModelTest : AppFunSpec() {
    private val dispatcher = StandardTestDispatcher()

    init {
        extension(MainDispatcherExtension(dispatcher))

        test("open loads config session and first conflict page") {
            runTest(dispatcher) {
                val repo = FakeRemoteSyncCenterRepository()
                val viewModel =
                    SyncCenterViewModel(
                        remoteSyncCenter = RemoteSyncCenterUseCase(repo),
                        dispatcherProvider = TestDispatcherProvider(dispatcher),
                    )

                viewModel.dispatch(SyncCenterIntent.Open(workspaceRoot = "/ws", isListDetail = false))
                advanceUntilIdle()

                val load = viewModel.uiState.value.load.shouldBeInstanceOf<SyncCenterLoadState.Ready>()
                load.config.backend shouldBe RemoteSyncBackendLabel.Git
                load.session.phase shouldBe RemoteSyncSessionPhase.ConflictOpen
                load.items.map { it.path } shouldContainExactly listOf("memo/a.md", "media/x.bin")
                load.conflictPage.conflictRevision shouldBe 3L
                repo.lastListCursor shouldBe 0
                repo.lastListLimit shouldBe SYNC_CENTER_CONFLICT_PAGE_LIMIT
            }
        }

        test("load more appends next page paths") {
            runTest(dispatcher) {
                val repo = FakeRemoteSyncCenterRepository(includeNextCursor = true)
                val viewModel =
                    SyncCenterViewModel(
                        remoteSyncCenter = RemoteSyncCenterUseCase(repo),
                        dispatcherProvider = TestDispatcherProvider(dispatcher),
                    )

                viewModel.dispatch(SyncCenterIntent.Open(workspaceRoot = "/ws", isListDetail = false))
                advanceUntilIdle()
                viewModel.dispatch(SyncCenterIntent.LoadMoreConflicts)
                advanceUntilIdle()

                val load = viewModel.uiState.value.load.shouldBeInstanceOf<SyncCenterLoadState.Ready>()
                load.items.map { it.path } shouldContainExactly
                    listOf("memo/a.md", "media/x.bin", "memo/b.md")
                repo.lastListCursor shouldBe 100
            }
        }

        test("apply keep_local removes path using expected revision fence") {
            runTest(dispatcher) {
                val repo = FakeRemoteSyncCenterRepository()
                val viewModel =
                    SyncCenterViewModel(
                        remoteSyncCenter = RemoteSyncCenterUseCase(repo),
                        dispatcherProvider = TestDispatcherProvider(dispatcher),
                    )

                viewModel.dispatch(SyncCenterIntent.Open(workspaceRoot = "/ws", isListDetail = false))
                advanceUntilIdle()
                viewModel.dispatch(
                    SyncCenterIntent.SetResolutionKind(
                        path = "memo/a.md",
                        kind = RemoteSyncConflictResolution.KIND_KEEP_LOCAL,
                    ),
                )
                viewModel.dispatch(SyncCenterIntent.ApplyResolutions)
                advanceUntilIdle()

                repo.lastExpectedRevision shouldBe 3L
                repo.lastResolutions.shouldNotBeNull().single().kind shouldBe
                    RemoteSyncConflictResolution.KIND_KEEP_LOCAL
                val load = viewModel.uiState.value.load.shouldBeInstanceOf<SyncCenterLoadState.Ready>()
                load.items.map { it.path } shouldContainExactly listOf("media/x.bin")
                load.appliedPaths shouldContainExactly listOf("memo/a.md")
                load.isResolving shouldBe false
            }
        }

        test("list failure surfaces category and code") {
            runTest(dispatcher) {
                val repo =
                    FakeRemoteSyncCenterRepository(
                        listFailure =
                            RemoteSyncCenterFailure(
                                category = "conflict",
                                code = "corrupt_session",
                                retryDisposition = "never",
                                diagnostic = "session checksum mismatch",
                            ),
                    )
                val viewModel =
                    SyncCenterViewModel(
                        remoteSyncCenter = RemoteSyncCenterUseCase(repo),
                        dispatcherProvider = TestDispatcherProvider(dispatcher),
                    )

                viewModel.dispatch(SyncCenterIntent.Open(workspaceRoot = "/ws", isListDetail = false))
                advanceUntilIdle()

                val load = viewModel.uiState.value.load.shouldBeInstanceOf<SyncCenterLoadState.Failed>()
                load.message shouldBe "conflict:corrupt_session"
            }
        }

        test("stale resolve leaves ready state with error and not resolving") {
            runTest(dispatcher) {
                val repo =
                    FakeRemoteSyncCenterRepository(
                        resolveFailure =
                            RemoteSyncCenterFailure(
                                category = "conflict",
                                code = "conflict_revision_stale",
                                retryDisposition = "after_user_action",
                                diagnostic = "expected 3 got 4",
                            ),
                    )
                val viewModel =
                    SyncCenterViewModel(
                        remoteSyncCenter = RemoteSyncCenterUseCase(repo),
                        dispatcherProvider = TestDispatcherProvider(dispatcher),
                    )

                viewModel.dispatch(SyncCenterIntent.Open(workspaceRoot = "/ws", isListDetail = false))
                advanceUntilIdle()
                viewModel.dispatch(
                    SyncCenterIntent.SetResolutionKind(
                        path = "memo/a.md",
                        kind = RemoteSyncConflictResolution.KIND_KEEP_REMOTE,
                    ),
                )
                viewModel.dispatch(SyncCenterIntent.ApplyResolutions)
                advanceUntilIdle()

                val load = viewModel.uiState.value.load.shouldBeInstanceOf<SyncCenterLoadState.Ready>()
                load.isResolving shouldBe false
                load.lastError shouldBe "conflict:conflict_revision_stale"
                load.items.size shouldBe 2
            }
        }

        test("select markdown loads non-null bodies from repository detail port") {
            runTest(dispatcher) {
                val repo =
                    FakeRemoteSyncCenterRepository(
                        markdownBodies =
                            MarkdownBodies(
                                base = "base-body",
                                local = "local-body",
                                remote = "remote-body",
                            ),
                    )
                val viewModel =
                    SyncCenterViewModel(
                        remoteSyncCenter = RemoteSyncCenterUseCase(repo),
                        dispatcherProvider = TestDispatcherProvider(dispatcher),
                    )

                viewModel.dispatch(SyncCenterIntent.Open(workspaceRoot = "/ws", isListDetail = false))
                advanceUntilIdle()
                viewModel.dispatch(SyncCenterIntent.SelectConflict("memo/a.md"))
                advanceUntilIdle()

                repo.lastMarkdownDetailPath shouldBe "memo/a.md"
                val load = viewModel.uiState.value.load.shouldBeInstanceOf<SyncCenterLoadState.Ready>()
                load.selectedPath shouldBe "memo/a.md"
                load.isLoadingDetail shouldBe false
                val facts = load.markdownDetailByPath["memo/a.md"].shouldNotBeNull()
                facts.baseBody shouldBe "base-body"
                facts.localBody shouldBe "local-body"
                facts.remoteBody shouldBe "remote-body"
                facts.localDigest shouldBe "l"
                // Live-path helper prefers state-carried facts with bodies.
                val fromState =
                    markdownFactsFromState(
                        load,
                        load.items.first { it.path == "memo/a.md" },
                    )
                fromState.localBody shouldBe "local-body"
                fromState.remoteBody shouldBe "remote-body"
                fromState.baseBody shouldBe "base-body"
            }
        }

        test("select binary loads digests only and never invents text preview") {
            runTest(dispatcher) {
                val repo = FakeRemoteSyncCenterRepository()
                val viewModel =
                    SyncCenterViewModel(
                        remoteSyncCenter = RemoteSyncCenterUseCase(repo),
                        dispatcherProvider = TestDispatcherProvider(dispatcher),
                    )

                viewModel.dispatch(SyncCenterIntent.Open(workspaceRoot = "/ws", isListDetail = false))
                advanceUntilIdle()
                viewModel.dispatch(SyncCenterIntent.SelectConflict("media/x.bin"))
                advanceUntilIdle()

                repo.lastBinaryDetailPath shouldBe "media/x.bin"
                repo.markdownDetailCallCount shouldBe 0
                val load = viewModel.uiState.value.load.shouldBeInstanceOf<SyncCenterLoadState.Ready>()
                load.isLoadingDetail shouldBe false
                val facts = load.binaryDetailByPath["media/x.bin"].shouldNotBeNull()
                facts.localDigest shouldBe "lb"
                facts.remoteDigest shouldBe "rb"
                facts.baselineDigest shouldBe "bb"
                facts.sourceLabel shouldBe "remote_sync"
                facts.mimeType.shouldBeNull()
                facts.sizeBytes.shouldBeNull()
                // Binary map only — no markdown bodies invented for binary path.
                load.markdownDetailByPath["media/x.bin"].shouldBeNull()
                val fromState =
                    binaryFactsFromState(
                        load,
                        load.items.first { it.path == "media/x.bin" },
                    )
                fromState.localDigest shouldBe "lb"
                fromState.sourceLabel shouldBe "remote_sync"
            }
        }

        test("markdown detail failure fails closed without inventing bodies") {
            runTest(dispatcher) {
                val repo =
                    FakeRemoteSyncCenterRepository(
                        markdownDetailFailure =
                            RemoteSyncCenterFailure(
                                category = "conflict",
                                code = "conflict_artifact_invalid_utf8",
                                retryDisposition = "never",
                                diagnostic = "invalid utf-8 in local artifact",
                            ),
                    )
                val viewModel =
                    SyncCenterViewModel(
                        remoteSyncCenter = RemoteSyncCenterUseCase(repo),
                        dispatcherProvider = TestDispatcherProvider(dispatcher),
                    )

                viewModel.dispatch(SyncCenterIntent.Open(workspaceRoot = "/ws", isListDetail = false))
                advanceUntilIdle()
                viewModel.dispatch(SyncCenterIntent.SelectConflict("memo/a.md"))
                advanceUntilIdle()

                val load = viewModel.uiState.value.load.shouldBeInstanceOf<SyncCenterLoadState.Ready>()
                load.isLoadingDetail shouldBe false
                load.lastError shouldBe "conflict:conflict_artifact_invalid_utf8"
                load.markdownDetailByPath["memo/a.md"].shouldBeNull()
                // Digest-only fallback still available for Compose until success.
                val fromState =
                    markdownFactsFromState(
                        load,
                        load.items.first { it.path == "memo/a.md" },
                    )
                fromState.localBody.shouldBeNull()
                fromState.remoteBody.shouldBeNull()
                fromState.baseBody.shouldBeNull()
            }
        }
    }
}

private data class MarkdownBodies(
    val base: String?,
    val local: String?,
    val remote: String?,
)

private class FakeRemoteSyncCenterRepository(
    private val includeNextCursor: Boolean = false,
    private val listFailure: RemoteSyncCenterFailure? = null,
    private val resolveFailure: RemoteSyncCenterFailure? = null,
    private val markdownBodies: MarkdownBodies? = null,
    private val markdownDetailFailure: RemoteSyncCenterFailure? = null,
    private val binaryDetailFailure: RemoteSyncCenterFailure? = null,
) : RemoteSyncCenterRepository {
    var lastListCursor: Int? = null
    var lastListLimit: Int? = null
    var lastExpectedRevision: Long? = null
    var lastResolutions: List<RemoteSyncConflictResolution>? = null
    var lastMarkdownDetailPath: String? = null
    var lastBinaryDetailPath: String? = null
    var markdownDetailCallCount: Int = 0
    var binaryDetailCallCount: Int = 0

    override fun configSummary(workspaceRoot: String): RemoteSyncConfigSummary =
        RemoteSyncConfigSummary(
            backend = RemoteSyncBackendLabel.Git,
            attentionCount = 2,
            lastVerifiedAtEpochMillis = 1_700_000_000_000L,
            schedulePolicyLabel = "interval_1h",
        )

    override fun sessionProgress(workspaceRoot: String): RemoteSyncSessionProgress =
        RemoteSyncSessionProgress(
            phase = RemoteSyncSessionPhase.ConflictOpen,
            completedActions = 1,
            totalActions = 5,
            canCancel = true,
        )

    override fun listConflicts(
        workspaceRoot: String,
        cursor: Int,
        limit: Int,
    ): RemoteSyncConflictPage {
        listFailure?.let { throw it }
        lastListCursor = cursor
        lastListLimit = limit
        return if (cursor == 0) {
            RemoteSyncConflictPage(
                sessionId = "session-1",
                conflictRevision = 3L,
                items =
                    listOf(
                        RemoteSyncConflictPath(
                            path = "memo/a.md",
                            kind = "markdown",
                            localDigest = "l",
                            remoteDigest = "r",
                            baselineDigest = "b",
                            remoteTokenPresent = true,
                            localArtifactRef = "la",
                            remoteArtifactRef = "ra",
                            baselineArtifactRef = "ba",
                            status = RemoteSyncConflictPathStatus.Open,
                        ),
                        RemoteSyncConflictPath(
                            path = "media/x.bin",
                            kind = "binary",
                            localDigest = "lb",
                            remoteDigest = "rb",
                            baselineDigest = "bb",
                            remoteTokenPresent = false,
                            localArtifactRef = "a1",
                            remoteArtifactRef = "a2",
                            status = RemoteSyncConflictPathStatus.Open,
                        ),
                    ),
                nextCursor = if (includeNextCursor) 100 else null,
            )
        } else {
            RemoteSyncConflictPage(
                sessionId = "session-1",
                conflictRevision = 3L,
                items =
                    listOf(
                        RemoteSyncConflictPath(
                            path = "memo/b.md",
                            kind = "markdown",
                            localDigest = null,
                            remoteDigest = null,
                            baselineDigest = null,
                            remoteTokenPresent = false,
                            localArtifactRef = null,
                            remoteArtifactRef = null,
                            status = RemoteSyncConflictPathStatus.Open,
                        ),
                    ),
                nextCursor = null,
            )
        }
    }

    override fun resolveConflicts(
        workspaceRoot: String,
        expectedRevision: Long,
        resolutions: List<RemoteSyncConflictResolution>,
    ): RemoteSyncConflictResolveResult {
        resolveFailure?.let { throw it }
        lastExpectedRevision = expectedRevision
        lastResolutions = resolutions
        return RemoteSyncConflictResolveResult(
            sessionId = "session-1",
            conflictRevision = expectedRevision + 1,
            appliedPaths = resolutions.map { it.path },
        )
    }

    override fun markdownConflictFacts(
        workspaceRoot: String,
        path: RemoteSyncConflictPath,
        mergedDraft: String?,
    ): RemoteSyncMarkdownConflictFacts {
        markdownDetailCallCount += 1
        lastMarkdownDetailPath = path.path
        markdownDetailFailure?.let { throw it }
        val bodies = markdownBodies
        return RemoteSyncMarkdownConflictFacts(
            path = path.path,
            baseDigest = path.baselineDigest,
            localDigest = path.localDigest,
            remoteDigest = path.remoteDigest,
            baseBody = bodies?.base,
            localBody = bodies?.local,
            remoteBody = bodies?.remote,
            mergedDraft = mergedDraft,
        )
    }

    override fun binaryConflictFacts(
        workspaceRoot: String,
        path: RemoteSyncConflictPath,
    ): RemoteSyncBinaryConflictFacts {
        binaryDetailCallCount += 1
        lastBinaryDetailPath = path.path
        binaryDetailFailure?.let { throw it }
        return RemoteSyncBinaryConflictFacts(
            path = path.path,
            mimeType = null,
            sizeBytes = null,
            localDigest = path.localDigest,
            remoteDigest = path.remoteDigest,
            baselineDigest = path.baselineDigest,
            sourceLabel = "remote_sync",
        )
    }
}

private class TestDispatcherProvider(
    private val dispatcher: CoroutineDispatcher,
) : DispatcherProvider {
    override val main: CoroutineDispatcher get() = dispatcher
    override val io: CoroutineDispatcher get() = dispatcher
    override val default: CoroutineDispatcher get() = dispatcher
    override val unconfined: CoroutineDispatcher get() = dispatcher
}
