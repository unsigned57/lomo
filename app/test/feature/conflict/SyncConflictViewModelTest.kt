package com.lomo.app.feature.conflict

import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.fakes.FakeMemoStore
import com.lomo.domain.model.RemoteSyncBackendLabel
import com.lomo.domain.model.RemoteSyncBinaryConflictFacts
import com.lomo.domain.model.RemoteSyncConfigSummary
import com.lomo.domain.model.RemoteSyncConflictPage
import com.lomo.domain.model.RemoteSyncConflictPath
import com.lomo.domain.model.RemoteSyncConflictPathStatus
import com.lomo.domain.model.RemoteSyncConflictResolution
import com.lomo.domain.model.RemoteSyncConflictResolveResult
import com.lomo.domain.model.RemoteSyncMarkdownConflictFacts
import com.lomo.domain.model.RemoteSyncSessionPhase
import com.lomo.domain.model.RemoteSyncSessionProgress
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncReviewItem
import com.lomo.domain.model.SyncReviewItemState
import com.lomo.domain.model.SyncReviewResolution
import com.lomo.domain.model.SyncReviewResolutionChoice
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.model.SyncReviewSessionKind
import com.lomo.domain.model.UnifiedSyncOperation
import com.lomo.domain.model.UnifiedSyncResult
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.repository.RemoteSyncCenterRepository
import com.lomo.domain.repository.SyncConflictBackupRepository
import com.lomo.domain.repository.UnifiedSyncProvider
import com.lomo.domain.usecase.BackupSyncConflictFilesUseCase
import com.lomo.domain.usecase.RemoteSyncConflictDialogUseCase
import com.lomo.domain.usecase.SyncProviderRegistry
import com.lomo.domain.usecase.SyncReviewResolutionUseCase
import com.lomo.app.testing.fakes.FakeMemoMutationRepository
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Capability: Original conflict dialog over Rust RemoteSyncCenterRepository + independent
 *   Sync Inbox review path.
 * - Scenarios:
 *   - Given a remote OpenSession, when shown, then dialog exposes bodies and suggested choices.
 *   - Given heuristics, when S3 remote is strict superset, then KEEP_REMOTE is preselected.
 *   - Given apply with Rust session, when resolve succeeds, then backup + expected-revision resolve
 *     hide the dialog.
 *   - Given apply without remote session, when applyResolution, then fail-closed (no Kotlin engine).
 *   - Given inbox review, when apply, then SyncReviewResolutionUseCase path runs independently.
 * - Observable outcomes: ViewModel state, backup log, remote resolve kinds/revision.
 * - Excludes: Compose UI, BoltFFI/JNI, Sync Center list-detail as primary UX.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncConflictViewModelTest : AppFunSpec() {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var operationLog: MutableList<ConflictOperation>
    private lateinit var backupRepository: RecordingConflictBackupRepository
    private lateinit var memoRepository: FakeMemoStore
    private lateinit var remoteRepo: RecordingRemoteSyncCenterRepository
    private lateinit var inboxProvider: RecordingUnifiedSyncProvider

    init {
        extension(MainDispatcherExtension(dispatcher))

        beforeTest {
            operationLog = mutableListOf()
            backupRepository = RecordingConflictBackupRepository(operationLog)
            memoRepository = FakeMemoStore()
            remoteRepo = RecordingRemoteSyncCenterRepository()
            inboxProvider =
                RecordingUnifiedSyncProvider(
                    backendType = SyncBackendType.INBOX,
                    operationLog = operationLog,
                )
        }

        test("showRemoteConflictSession exposes showing state with empty choices for non-heuristic files") {
            val viewModel = createViewModel()
            val session = remoteSession(files = conflictFiles())

            viewModel.showRemoteConflictSession(session)

            viewModel.state.value shouldBe
                SyncConflictDialogState.Showing(
                    conflictSet = session.conflictSet,
                    perFileChoices = emptyMap<String, SyncConflictResolutionChoice>().toImmutableMap(),
                    expandedFilePath = null,
                    isResolving = false,
                )
        }

        test("showRemoteConflictSession preselects remote when s3 remote content is a strict superset") {
            val viewModel = createViewModel()
            val session =
                remoteSession(
                    source = SyncBackendType.S3,
                    files =
                        listOf(
                            SyncConflictFile(
                                relativePath = "memos/2026_03_24.md",
                                localContent = "alpha\n\nbeta",
                                remoteContent = "alpha\n\nbeta\n\ngamma",
                                isBinary = false,
                            ),
                        ),
                )

            viewModel.showRemoteConflictSession(session)

            viewModel.state.value shouldBe
                SyncConflictDialogState.Showing(
                    conflictSet = session.conflictSet,
                    perFileChoices =
                        mapOf(
                            "memos/2026_03_24.md" to SyncConflictResolutionChoice.KEEP_REMOTE,
                        ).toImmutableMap(),
                    expandedFilePath = null,
                    isResolving = false,
                )
        }

        test("showRemoteConflictSession preselects merge when s3 text inserts do not overlap") {
            val viewModel = createViewModel()
            val session =
                remoteSession(
                    source = SyncBackendType.S3,
                    files =
                        listOf(
                            SyncConflictFile(
                                relativePath = "memos/2026_03_24.md",
                                localContent = "start\nlocal\nmiddle\nend",
                                remoteContent = "start\nmiddle\nremote\nend",
                                isBinary = false,
                            ),
                        ),
                )

            viewModel.showRemoteConflictSession(session)

            viewModel.state.value shouldBe
                SyncConflictDialogState.Showing(
                    conflictSet = session.conflictSet,
                    perFileChoices =
                        mapOf(
                            "memos/2026_03_24.md" to SyncConflictResolutionChoice.MERGE_TEXT,
                        ).toImmutableMap(),
                    expandedFilePath = null,
                    isResolving = false,
                )
        }

        test("showReviewDialog preselects merge for inbox short disjoint memo content") {
            val viewModel = createViewModel()
            val review =
                reviewSession(
                    items =
                        listOf(
                            SyncReviewItem(
                                relativePath = "inbox/2026_04_15.md",
                                localContent = "start\nlocal\nmiddle\nend",
                                incomingContent = "start\nmiddle\nremote\nend",
                                isBinary = false,
                            ),
                        ),
                )

            viewModel.showReviewDialog(review)

            viewModel.state.value shouldBe
                SyncConflictDialogState.ReviewShowing(
                    reviewSession = review,
                    perItemChoices =
                        mapOf(
                            "inbox/2026_04_15.md" to SyncReviewResolutionChoice.MERGE_TEXT,
                        ).toImmutableMap(),
                    expandedFilePath = null,
                    isResolving = false,
                )
        }

        test("showReviewDialog leaves blocked inbox items unselected") {
            val viewModel = createViewModel()
            val review =
                reviewSession(
                    items =
                        listOf(
                            SyncReviewItem(
                                relativePath = "inbox/ok.md",
                                localContent = "a",
                                incomingContent = "a\nb",
                                isBinary = false,
                                state = SyncReviewItemState.CONTENT_DIFFERENCE,
                            ),
                            SyncReviewItem(
                                relativePath = "inbox/blocked.md",
                                localContent = "x",
                                incomingContent = "y",
                                isBinary = false,
                                state = SyncReviewItemState.BLOCKED,
                                message = "blocked",
                            ),
                        ),
                )

            viewModel.showReviewDialog(review)

            val state = viewModel.state.value as SyncConflictDialogState.ReviewShowing
            state.blockedPaths shouldBe setOf("inbox/blocked.md").toImmutableSet()
            state.perItemChoices.containsKey("inbox/blocked.md") shouldBe false
        }

        test("showRemoteConflictSession preselects local when local metadata is much newer for identical content") {
            val viewModel = createViewModel()
            val session =
                remoteSession(
                    files =
                        listOf(
                            SyncConflictFile(
                                relativePath = "memos/2026_03_24.md",
                                localContent = "same",
                                remoteContent = "same",
                                isBinary = false,
                                localLastModified = 10_000_000L,
                                remoteLastModified = 1_000L,
                            ),
                        ),
                )

            viewModel.showRemoteConflictSession(session)

            viewModel.state.value shouldBe
                SyncConflictDialogState.Showing(
                    conflictSet = session.conflictSet,
                    perFileChoices =
                        mapOf(
                            "memos/2026_03_24.md" to SyncConflictResolutionChoice.KEEP_LOCAL,
                        ).toImmutableMap(),
                    expandedFilePath = null,
                    isResolving = false,
                )
        }

        test("showRemoteConflictSession preselects the non-empty side when the remote content was deleted") {
            val viewModel = createViewModel()
            val session =
                remoteSession(
                    files =
                        listOf(
                            SyncConflictFile(
                                relativePath = "memos/2026_03_24.md",
                                localContent = "local only",
                                remoteContent = "",
                                isBinary = false,
                            ),
                        ),
                )

            viewModel.showRemoteConflictSession(session)

            viewModel.state.value shouldBe
                SyncConflictDialogState.Showing(
                    conflictSet = session.conflictSet,
                    perFileChoices =
                        mapOf(
                            "memos/2026_03_24.md" to SyncConflictResolutionChoice.KEEP_LOCAL,
                        ).toImmutableMap(),
                    expandedFilePath = null,
                    isResolving = false,
                )
        }

        test("setAllChoices applies bulk selection") {
            val viewModel = createViewModel()
            val session = remoteSession()
            viewModel.showRemoteConflictSession(session)

            viewModel.setAllChoices(SyncConflictResolutionChoice.KEEP_REMOTE)

            val state = viewModel.state.value as SyncConflictDialogState.Showing
            state.perFileChoices shouldBe
                mapOf(
                    "memos/2026_03_24.md" to SyncConflictResolutionChoice.KEEP_REMOTE,
                    "images/photo.jpg" to SyncConflictResolutionChoice.KEEP_REMOTE,
                ).toImmutableMap()
        }

        test("toggleExpandedFile expands and collapses") {
            val viewModel = createViewModel()
            viewModel.showRemoteConflictSession(remoteSession())

            viewModel.toggleExpandedFile("memos/2026_03_24.md")
            (viewModel.state.value as SyncConflictDialogState.Showing).expandedFilePath shouldBe "memos/2026_03_24.md"

            viewModel.toggleExpandedFile("memos/2026_03_24.md")
            (viewModel.state.value as SyncConflictDialogState.Showing).expandedFilePath shouldBe null
        }

        test("applyResolution backs up files and resolves via Rust expected revision") {
            runTest {
                remoteRepo.postResolveEmpty = true
                val viewModel = createViewModel()
                val session = remoteSession()
                viewModel.showRemoteConflictSession(session)
                viewModel.setFileChoice("memos/2026_03_24.md", SyncConflictResolutionChoice.KEEP_LOCAL)
                viewModel.setFileChoice("images/photo.jpg", SyncConflictResolutionChoice.KEEP_REMOTE)

                viewModel.applyResolution()
                dispatcher.scheduler.advanceUntilIdle()

                viewModel.state.value shouldBe SyncConflictDialogState.Hidden
                operationLog shouldBe
                    listOf(
                        ConflictOperation.Backup(session.conflictSet.files),
                    )
                remoteRepo.lastExpectedRevision shouldBe session.conflictRevision
                remoteRepo.lastResolutions shouldBe
                    listOf(
                        RemoteSyncConflictResolution(
                            path = "memos/2026_03_24.md",
                            kind = RemoteSyncConflictResolution.KIND_KEEP_LOCAL,
                        ),
                        RemoteSyncConflictResolution(
                            path = "images/photo.jpg",
                            kind = RemoteSyncConflictResolution.KIND_KEEP_REMOTE,
                        ),
                    )
            }
        }

        test("applyResolution without remote session fails closed without backup") {
            runTest {
                val viewModel = createViewModel()
                viewModel.showConflictDialog(conflictSet())
                viewModel.setAllChoices(SyncConflictResolutionChoice.KEEP_LOCAL)

                viewModel.applyResolution()
                dispatcher.scheduler.advanceUntilIdle()

                val state = viewModel.state.value as SyncConflictDialogState.Showing
                state.isResolving shouldBe false
                operationLog shouldBe emptyList()
                remoteRepo.lastResolutions shouldBe null
            }
        }

        test("applyResolution keeps dialog open and clears resolving after backup failure") {
            runTest {
                val viewModel = createViewModel()
                val session = remoteSession()
                viewModel.showRemoteConflictSession(session)
                viewModel.setAllChoices(SyncConflictResolutionChoice.KEEP_LOCAL)
                backupRepository.failure = IllegalStateException("backup failed")

                viewModel.applyResolution()

                val resolvingState = viewModel.state.value as SyncConflictDialogState.Showing
                resolvingState.isResolving shouldBe true

                dispatcher.scheduler.advanceUntilIdle()

                val state = viewModel.state.value as SyncConflictDialogState.Showing
                state.conflictSet shouldBe session.conflictSet
                state.isResolving shouldBe false
                operationLog shouldBe emptyList()
            }
        }

        test("applyResolution keeps pending open subset after partial Rust resolve") {
            runTest {
                val remaining =
                    SyncConflictFile(
                        relativePath = "images/photo.jpg",
                        localContent = null,
                        remoteContent = null,
                        isBinary = true,
                    )
                remoteRepo.remainingAfterResolve =
                    listOf(
                        RemoteSyncConflictPath(
                            path = remaining.relativePath,
                            kind = "binary",
                            localDigest = "l",
                            remoteDigest = "r",
                            baselineDigest = "b",
                            remoteTokenPresent = true,
                            localArtifactRef = null,
                            remoteArtifactRef = null,
                            status = RemoteSyncConflictPathStatus.Open,
                        ),
                    )
                val viewModel = createViewModel()
                val session =
                    remoteSession(
                        source = SyncBackendType.S3,
                        files =
                            listOf(
                                SyncConflictFile(
                                    relativePath = "memos/2026_03_24.md",
                                    localContent = "alpha\n\nbeta",
                                    remoteContent = "alpha\n\nbeta\n\ngamma",
                                    isBinary = false,
                                ),
                                remaining,
                            ),
                    )
                viewModel.showRemoteConflictSession(session)
                viewModel.setFileChoice("memos/2026_03_24.md", SyncConflictResolutionChoice.KEEP_REMOTE)
                viewModel.setFileChoice("images/photo.jpg", SyncConflictResolutionChoice.SKIP_FOR_NOW)

                viewModel.applyResolution()
                dispatcher.scheduler.advanceUntilIdle()

                val state = viewModel.state.value as SyncConflictDialogState.Showing
                state.conflictSet.files.map { it.relativePath } shouldBe listOf("images/photo.jpg")
                state.isResolving shouldBe false
            }
        }

        test("applyResolution resolves inbox review independently of remote kernel") {
            runTest {
                val viewModel = createViewModel()
                val review =
                    reviewSession(
                        items =
                            listOf(
                                SyncReviewItem(
                                    relativePath = "inbox/2026_04_15.md",
                                    localContent = "alpha\nbeta",
                                    incomingContent = "alpha\nbeta\ngamma",
                                    isBinary = false,
                                ),
                            ),
                    )
                viewModel.showReviewDialog(review)
                viewModel.setAllReviewItemChoices(SyncReviewResolutionChoice.KEEP_INCOMING)

                viewModel.applyResolution()
                dispatcher.scheduler.advanceUntilIdle()

                viewModel.state.value shouldBe SyncConflictDialogState.Hidden
                operationLog shouldBe
                    listOf(
                        ConflictOperation.ResolveReview(
                            review = review,
                            resolution =
                                SyncReviewResolution(
                                    mapOf(
                                        "inbox/2026_04_15.md" to SyncReviewResolutionChoice.KEEP_INCOMING,
                                    ).toImmutableMap(),
                                ),
                        ),
                    )
                remoteRepo.lastResolutions shouldBe null
            }
        }

        test("applyResolution does nothing when dialog is hidden") {
            runTest {
                val viewModel = createViewModel()

                viewModel.applyResolution()
                advanceUntilIdle()

                viewModel.state.value shouldBe SyncConflictDialogState.Hidden
                operationLog shouldBe emptyList()
            }
        }
    }

    private fun createViewModel(): SyncConflictViewModel =
        SyncConflictViewModel(
            remoteSyncConflictDialogUseCase =
                RemoteSyncConflictDialogUseCase(
                    remoteSyncCenterRepository = remoteRepo,
                    memoRepository = FakeMemoMutationRepository(memoRepository),
                ),
            syncReviewResolutionUseCase =
                SyncReviewResolutionUseCase(
                    syncProviderRegistry = SyncProviderRegistry(setOf(inboxProvider)),
                ),
            backupSyncConflictFilesUseCase = BackupSyncConflictFilesUseCase(backupRepository),
        )

    private fun conflictFiles(): List<SyncConflictFile> =
        listOf(
            SyncConflictFile(
                relativePath = "memos/2026_03_24.md",
                localContent = "start\nlocal memo\nend",
                remoteContent = "start\nremote memo\nend",
                isBinary = false,
            ),
            SyncConflictFile(
                relativePath = "images/photo.jpg",
                localContent = null,
                remoteContent = null,
                isBinary = true,
            ),
        )

    private fun conflictSet(
        source: SyncBackendType = SyncBackendType.GIT,
        files: List<SyncConflictFile> = conflictFiles(),
    ): SyncConflictSet =
        SyncConflictSet(
            source = source,
            files = files,
            timestamp = 123L,
        )

    private fun remoteSession(
        source: SyncBackendType = SyncBackendType.GIT,
        files: List<SyncConflictFile> = conflictFiles(),
        revision: Long = 11L,
    ): RemoteSyncConflictDialogUseCase.OpenSession =
        RemoteSyncConflictDialogUseCase.OpenSession(
            workspaceRoot = "/ws",
            conflictSet = conflictSet(source = source, files = files),
            conflictRevision = revision,
            sessionId = "sess-test",
        )

    private fun reviewSession(
        source: SyncBackendType = SyncBackendType.INBOX,
        items: List<SyncReviewItem>,
        kind: SyncReviewSessionKind = SyncReviewSessionKind.SYNC_INBOX_IMPORT_REVIEW,
    ): SyncReviewSession =
        SyncReviewSession(
            source = source,
            items = items,
            timestamp = 123L,
            kind = kind,
        )

    private sealed interface ConflictOperation {
        data class Backup(
            val files: List<SyncConflictFile>,
        ) : ConflictOperation

        data class ResolveReview(
            val review: SyncReviewSession,
            val resolution: SyncReviewResolution,
        ) : ConflictOperation
    }

    private class RecordingConflictBackupRepository(
        private val operationLog: MutableList<ConflictOperation>,
    ) : SyncConflictBackupRepository {
        var failure: Throwable? = null

        override suspend fun backupFiles(
            files: List<SyncConflictFile>,
            localFileReader: suspend (String) -> ByteArray?,
        ) {
            failure?.let { throw it }
            operationLog += ConflictOperation.Backup(files)
        }
    }

    private class RecordingUnifiedSyncProvider(
        override val backendType: SyncBackendType,
        private val operationLog: MutableList<ConflictOperation>,
    ) : UnifiedSyncProvider {
        var resolveResult: UnifiedSyncResult =
            UnifiedSyncResult.Success(
                provider = backendType,
                message = "resolved",
            )

        override fun isEnabled(): Flow<Boolean> = flowOf(false)

        override fun isSyncOnRefreshEnabled(): Flow<Boolean> = flowOf(false)

        override fun syncState(): Flow<UnifiedSyncState> = flowOf(UnifiedSyncState.Idle)

        override suspend fun sync(operation: UnifiedSyncOperation): UnifiedSyncResult =
            UnifiedSyncResult.Success(
                provider = backendType,
                message = "synced",
            )

        override suspend fun resolveConflicts(
            resolution: com.lomo.domain.model.SyncConflictResolution,
            conflictSet: SyncConflictSet,
        ): UnifiedSyncResult = resolveResult

        override suspend fun resolveReview(
            resolution: SyncReviewResolution,
            review: SyncReviewSession,
        ): UnifiedSyncResult {
            operationLog +=
                ConflictOperation.ResolveReview(
                    review = review,
                    resolution = resolution,
                )
            return resolveResult
        }
    }

    private class RecordingRemoteSyncCenterRepository : RemoteSyncCenterRepository {
        var lastExpectedRevision: Long? = null
        var lastResolutions: List<RemoteSyncConflictResolution>? = null
        var postResolveEmpty: Boolean = false
        var remainingAfterResolve: List<RemoteSyncConflictPath> = emptyList()

        private var afterResolve = false

        override fun configSummary(workspaceRoot: String): RemoteSyncConfigSummary =
            RemoteSyncConfigSummary(
                backend = RemoteSyncBackendLabel.Git,
                attentionCount = 0,
                lastVerifiedAtEpochMillis = null,
                schedulePolicyLabel = null,
            )

        override fun sessionProgress(workspaceRoot: String): RemoteSyncSessionProgress =
            RemoteSyncSessionProgress(
                phase = RemoteSyncSessionPhase.ConflictOpen,
                completedActions = 0,
                totalActions = null,
                canCancel = false,
            )

        override fun listConflicts(
            workspaceRoot: String,
            cursor: Int,
            limit: Int,
        ): RemoteSyncConflictPage {
            val items =
                if (!afterResolve) {
                    emptyList()
                } else if (postResolveEmpty) {
                    emptyList()
                } else {
                    remainingAfterResolve
                }
            return RemoteSyncConflictPage(
                sessionId = "sess-test",
                conflictRevision = if (afterResolve) 12L else 11L,
                items = items,
                nextCursor = null,
            )
        }

        override fun resolveConflicts(
            workspaceRoot: String,
            expectedRevision: Long,
            resolutions: List<RemoteSyncConflictResolution>,
        ): RemoteSyncConflictResolveResult {
            lastExpectedRevision = expectedRevision
            lastResolutions = resolutions
            afterResolve = true
            return RemoteSyncConflictResolveResult(
                sessionId = "sess-test",
                conflictRevision = expectedRevision + 1,
                appliedPaths = resolutions.map { it.path },
            )
        }

        override fun markdownConflictFacts(
            workspaceRoot: String,
            path: RemoteSyncConflictPath,
            mergedDraft: String?,
        ): RemoteSyncMarkdownConflictFacts =
            RemoteSyncMarkdownConflictFacts(
                path = path.path,
                baseDigest = path.baselineDigest,
                localDigest = path.localDigest,
                remoteDigest = path.remoteDigest,
            )

        override fun binaryConflictFacts(
            workspaceRoot: String,
            path: RemoteSyncConflictPath,
        ): RemoteSyncBinaryConflictFacts =
            RemoteSyncBinaryConflictFacts(
                path = path.path,
                mimeType = null,
                sizeBytes = null,
                localDigest = path.localDigest,
                remoteDigest = path.remoteDigest,
                baselineDigest = path.baselineDigest,
                sourceLabel = "test",
            )
    }
}
