package com.lomo.app.feature.conflict

import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.fakes.FakeMemoStore
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictResolution
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
import com.lomo.domain.repository.SyncConflictBackupRepository
import com.lomo.domain.repository.UnifiedSyncProvider
import com.lomo.domain.usecase.BackupSyncConflictFilesUseCase
import com.lomo.domain.usecase.SyncConflictResolutionUseCase
import com.lomo.domain.usecase.SyncProviderRegistry
import com.lomo.domain.usecase.SyncReviewResolutionUseCase
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
 * - Capability: Conflict and review dialog state transitions, typed default choice preselection, and resolution application.
 * - Scenarios:
 *   - Given a conflict set, when the dialog is shown, then it displays correct initial showing state.
 *   - Given backend type and content heuristics, when conflicts are shown, then optimal default choices are suggested.
 *   - Given inbox blocked review items, when review state is shown, then blocked choices remain unselected.
 *   - Given user choice overrides or bulk selection, when choices change, then state applies choices appropriately.
 *   - Given apply resolution trigger, when resolution runs, then backup and resolve use cases run in order.
 * - Observable outcomes:
 *   - ViewModel state (Hidden, Showing, ReviewShowing), per-file conflict choices, and per-item review choices.
 * - TDD proof: RED observed with unresolved ReviewShowing/setReviewItemChoice references before the typed review state fix.
 * - Excludes: Compose UI dialog representation and sync engine transport operations.
 *
 * Test Change Justification:
 * - Reason category: API contract migration.
 * - Old behavior/assertion being replaced: SyncProviderRegistry accepted an ordered List of providers.
 * - Why old assertion is no longer correct: provider registration now models the Koin multibinding graph as an unordered Set.
 * - Coverage preserved by: existing ViewModel state assertions plus SyncProviderRegistryTest duplicate-provider coverage.
 * - Why this is not fitting the test to the implementation: observable conflict/review behavior is unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncConflictViewModelTest : AppFunSpec() {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var operationLog: MutableList<ConflictOperation>
    private lateinit var backupRepository: RecordingConflictBackupRepository
    private lateinit var memoRepository: FakeMemoStore
    private lateinit var syncProviders: Map<SyncBackendType, RecordingUnifiedSyncProvider>

    init {
        extension(MainDispatcherExtension(dispatcher))

        beforeTest {
            operationLog = mutableListOf()
            backupRepository = RecordingConflictBackupRepository(operationLog)
            memoRepository = FakeMemoStore()
            syncProviders =
                listOf(
                    SyncBackendType.GIT,
                    SyncBackendType.S3,
                    SyncBackendType.INBOX,
                ).associateWith { backendType ->
                    RecordingUnifiedSyncProvider(
                        backendType = backendType,
                        operationLog = operationLog,
                    )
                }
        }

        test("showConflictDialog exposes showing state with empty choices") {
            val viewModel = createViewModel()
            val conflictSet = conflictSet()

            viewModel.showConflictDialog(conflictSet)

            viewModel.state.value shouldBe SyncConflictDialogState.Showing(
                conflictSet = conflictSet,
                perFileChoices = emptyMap<String, SyncConflictResolutionChoice>().toImmutableMap(),
                expandedFilePath = null,
                isResolving = false,
            )
        }

        test("showConflictDialog preselects remote when s3 remote content is a strict superset") {
            val viewModel = createViewModel()
            val conflictSet = conflictSet(
                source = SyncBackendType.S3,
                files = listOf(
                    SyncConflictFile(
                        relativePath = "memos/2026_03_24.md",
                        localContent = "alpha\n\nbeta",
                        remoteContent = "alpha\n\nbeta\n\ngamma",
                        isBinary = false,
                    ),
                ),
            )

            viewModel.showConflictDialog(conflictSet)

            viewModel.state.value shouldBe SyncConflictDialogState.Showing(
                conflictSet = conflictSet,
                perFileChoices = mapOf(
                    "memos/2026_03_24.md" to SyncConflictResolutionChoice.KEEP_REMOTE,
                ).toImmutableMap(),
                expandedFilePath = null,
                isResolving = false,
            )
        }

        test("showConflictDialog preselects merge when s3 text inserts do not overlap") {
            val viewModel = createViewModel()
            val conflictSet = conflictSet(
                source = SyncBackendType.S3,
                files = listOf(
                    SyncConflictFile(
                        relativePath = "memos/2026_03_24.md",
                        localContent = "start\nlocal\nmiddle\nend",
                        remoteContent = "start\nmiddle\nremote\nend",
                        isBinary = false,
                    ),
                ),
            )

            viewModel.showConflictDialog(conflictSet)

            viewModel.state.value shouldBe SyncConflictDialogState.Showing(
                conflictSet = conflictSet,
                perFileChoices = mapOf(
                    "memos/2026_03_24.md" to SyncConflictResolutionChoice.MERGE_TEXT,
                ).toImmutableMap(),
                expandedFilePath = null,
                isResolving = false,
            )
        }

        test("showConflictDialog preselects merge for inbox short disjoint memo content") {
            val viewModel = createViewModel()
            val conflictSet = conflictSet(
                source = SyncBackendType.INBOX,
                files = listOf(
                    SyncConflictFile(
                        relativePath = "inbox/2026_04_15.md",
                        localContent = "local-only note",
                        remoteContent = "remote-only note",
                        isBinary = false,
                        localLastModified = 20L,
                        remoteLastModified = 10L,
                    ),
                ),
            )

            viewModel.showConflictDialog(conflictSet)

            viewModel.state.value shouldBe SyncConflictDialogState.Showing(
                conflictSet = conflictSet,
                perFileChoices = mapOf(
                    "inbox/2026_04_15.md" to SyncConflictResolutionChoice.MERGE_TEXT,
                ).toImmutableMap(),
                expandedFilePath = null,
                isResolving = false,
            )
        }

        test("showReviewDialog exposes typed review state and leaves blocked inbox review item unselected") {
            val viewModel = createViewModel()
            val review =
                reviewSession(
                source = SyncBackendType.INBOX,
                    items = listOf(
                        SyncReviewItem(
                        relativePath = "inbox/2026_04_16.md",
                        localContent = null,
                            incomingContent = "memo with image ![cover](cover.png)",
                        isBinary = false,
                            state = SyncReviewItemState.BLOCKED,
                            message = "Missing attachments: cover.png",
                        ),
                    ),
                )

            viewModel.showReviewDialog(review)

            viewModel.state.value shouldBe SyncConflictDialogState.ReviewShowing(
                reviewSession = review,
                perItemChoices = emptyMap<String, SyncReviewResolutionChoice>().toImmutableMap(),
                blockedPaths = setOf("inbox/2026_04_16.md").toImmutableSet(),
                expandedFilePath = null,
                isResolving = false,
            )
        }

        test("showConflictDialog preselects local when local metadata is much newer for identical content") {
            val viewModel = createViewModel()
            val conflictSet = conflictSet(
                source = SyncBackendType.S3,
                files = listOf(
                    SyncConflictFile(
                        relativePath = "memos/2026_03_24.md",
                        localContent = "same content",
                        remoteContent = "same content",
                        isBinary = false,
                        localLastModified = 1_200_000L,
                        remoteLastModified = 1_000L,
                    ),
                ),
            )

            viewModel.showConflictDialog(conflictSet)

            viewModel.state.value shouldBe SyncConflictDialogState.Showing(
                conflictSet = conflictSet,
                perFileChoices = mapOf(
                    "memos/2026_03_24.md" to SyncConflictResolutionChoice.KEEP_LOCAL,
                ).toImmutableMap(),
                expandedFilePath = null,
                isResolving = false,
            )
        }

        test("showConflictDialog preselects the non-empty side when the remote content was deleted") {
            val viewModel = createViewModel()
            val conflictSet = conflictSet(
                source = SyncBackendType.WEBDAV,
                files = listOf(
                    SyncConflictFile(
                        relativePath = "memos/2026_03_24.md",
                        localContent = "keep local",
                        remoteContent = "",
                        isBinary = false,
                    ),
                ),
            )

            viewModel.showConflictDialog(conflictSet)

            viewModel.state.value shouldBe SyncConflictDialogState.Showing(
                conflictSet = conflictSet,
                perFileChoices = mapOf(
                    "memos/2026_03_24.md" to SyncConflictResolutionChoice.KEEP_LOCAL,
                ).toImmutableMap(),
                expandedFilePath = null,
                isResolving = false,
            )
        }

        test("setAllChoices fills every file choice in showing state") {
            val viewModel = createViewModel()
            val conflictSet = conflictSet()
            viewModel.showConflictDialog(conflictSet)

            viewModel.setAllChoices(SyncConflictResolutionChoice.KEEP_REMOTE)

            val state = viewModel.state.value as SyncConflictDialogState.Showing
            state.perFileChoices shouldBe mapOf(
                "memos/2026_03_24.md" to SyncConflictResolutionChoice.KEEP_REMOTE,
                "images/photo.jpg" to SyncConflictResolutionChoice.KEEP_REMOTE,
            ).toImmutableMap()
        }

        test("acceptSuggestedChoices applies initial import review defaults without touching unsupported files") {
            val viewModel = createViewModel()
            val review =
                reviewSession(
                source = SyncBackendType.S3,
                    kind = SyncReviewSessionKind.INITIAL_IMPORT_PREVIEW,
                    items = listOf(
                        SyncReviewItem(
                        relativePath = "memos/2026_03_24.md",
                        localContent = "alpha\n\nbeta",
                            incomingContent = "alpha\n\nbeta\n\ngamma",
                        isBinary = false,
                    ),
                        SyncReviewItem(
                        relativePath = "memos/2026_03_25.md",
                        localContent = "start\nlocal\nmiddle\nend",
                            incomingContent = "start\nmiddle\nremote\nend",
                        isBinary = false,
                    ),
                        SyncReviewItem(
                        relativePath = "images/photo.jpg",
                        localContent = null,
                            incomingContent = null,
                        isBinary = true,
                    ),
                ),
            )
            viewModel.showReviewDialog(review)
            viewModel.setReviewItemChoice("images/photo.jpg", SyncReviewResolutionChoice.SKIP_FOR_NOW)

            viewModel.acceptSuggestedChoices()

            val state = viewModel.state.value as SyncConflictDialogState.ReviewShowing
            state.reviewSession shouldBe review
            state.isInitialImportPreview shouldBe true
            state.perItemChoices shouldBe mapOf(
                "memos/2026_03_24.md" to SyncReviewResolutionChoice.KEEP_INCOMING,
                "memos/2026_03_25.md" to SyncReviewResolutionChoice.MERGE_TEXT,
                "images/photo.jpg" to SyncReviewResolutionChoice.SKIP_FOR_NOW,
            ).toImmutableMap()
        }

        test("toggleExpandedFile toggles currently expanded path") {
            val viewModel = createViewModel()
            val conflictSet = conflictSet()
            viewModel.showConflictDialog(conflictSet)

            viewModel.toggleExpandedFile("memos/2026_03_24.md")
            (viewModel.state.value as SyncConflictDialogState.Showing).expandedFilePath shouldBe "memos/2026_03_24.md"

            viewModel.toggleExpandedFile("memos/2026_03_24.md")
            (viewModel.state.value as SyncConflictDialogState.Showing).expandedFilePath shouldBe null
        }

        test("applyResolution backs up files and hides dialog on success") {
            runTest {
                val viewModel = createViewModel()
                val conflictSet = conflictSet()
                viewModel.showConflictDialog(conflictSet)
                viewModel.setFileChoice("memos/2026_03_24.md", SyncConflictResolutionChoice.KEEP_LOCAL)
                viewModel.setFileChoice("images/photo.jpg", SyncConflictResolutionChoice.KEEP_REMOTE)

                viewModel.applyResolution()
                dispatcher.scheduler.advanceUntilIdle()

                viewModel.state.value shouldBe SyncConflictDialogState.Hidden
                operationLog shouldBe
                    listOf(
                        ConflictOperation.Backup(conflictSet.files),
                        ConflictOperation.Resolve(
                            conflictSet = conflictSet,
                            resolution =
                                SyncConflictResolution(
                                    mapOf(
                                        "memos/2026_03_24.md" to SyncConflictResolutionChoice.KEEP_LOCAL,
                                        "images/photo.jpg" to SyncConflictResolutionChoice.KEEP_REMOTE,
                                    ).toImmutableMap(),
                                ),
                        ),
                    )
            }
        }

        test("applyResolution keeps dialog open and clears resolving after failure") {
            runTest {
                val viewModel = createViewModel()
                val conflictSet = conflictSet()
                viewModel.showConflictDialog(conflictSet)
                viewModel.setAllChoices(SyncConflictResolutionChoice.KEEP_LOCAL)
                backupRepository.failure = IllegalStateException("backup failed")

                viewModel.applyResolution()

                val resolvingState = viewModel.state.value as SyncConflictDialogState.Showing
                resolvingState.isResolving shouldBe true

                dispatcher.scheduler.advanceUntilIdle()

                val state = viewModel.state.value as SyncConflictDialogState.Showing
                state.conflictSet shouldBe conflictSet
                state.isResolving shouldBe false
                state.perFileChoices shouldBe mapOf(
                    "memos/2026_03_24.md" to SyncConflictResolutionChoice.KEEP_LOCAL,
                    "images/photo.jpg" to SyncConflictResolutionChoice.KEEP_LOCAL,
                ).toImmutableMap()
                operationLog shouldBe emptyList()
            }
        }

        test("autoResolveSafeConflicts applies safe choices and defers unresolved s3 files") {
            runTest {
                val viewModel = createViewModel()
                val conflictSet = conflictSet(
                    source = SyncBackendType.S3,
                    files = listOf(
                        SyncConflictFile(
                            relativePath = "memos/2026_03_24.md",
                            localContent = "alpha\n\nbeta",
                            remoteContent = "alpha\n\nbeta\n\ngamma",
                            isBinary = false,
                        ),
                        SyncConflictFile(
                            relativePath = "memos/2026_03_25.md",
                            localContent = "start\nlocal only\nend",
                            remoteContent = "start\nremote only\nend",
                            isBinary = false,
                        ),
                    ),
                )
                val pending = conflictSet.copy(files = listOf(conflictSet.files[1]))
                viewModel.showConflictDialog(conflictSet)
                syncProviders.getValue(SyncBackendType.S3).resolveResult =
                    UnifiedSyncResult.Conflict(
                        provider = SyncBackendType.S3,
                        message = "pending",
                        conflicts = pending,
                    )

                viewModel.autoResolveSafeConflicts()
                dispatcher.scheduler.advanceUntilIdle()

                operationLog shouldBe
                    listOf(
                        ConflictOperation.Backup(listOf(conflictSet.files.first())),
                        ConflictOperation.Resolve(
                            conflictSet = conflictSet,
                            resolution =
                                SyncConflictResolution(
                                    mapOf(
                                        "memos/2026_03_24.md" to SyncConflictResolutionChoice.KEEP_REMOTE,
                                        "memos/2026_03_25.md" to SyncConflictResolutionChoice.SKIP_FOR_NOW,
                                    ).toImmutableMap(),
                                ),
                        ),
                    )
                viewModel.state.value shouldBe SyncConflictDialogState.Showing(
                    conflictSet = pending,
                    perFileChoices = mapOf(
                        "memos/2026_03_25.md" to SyncConflictResolutionChoice.SKIP_FOR_NOW,
                    ).toImmutableMap(),
                    expandedFilePath = null,
                    isResolving = false,
                )
            }
        }

        test("autoResolveSafeConflicts applies safe choices and defers unresolved inbox review files") {
            runTest {
                val viewModel = createViewModel()
                val review =
                    reviewSession(
                    source = SyncBackendType.INBOX,
                        items = listOf(
                            SyncReviewItem(
                            relativePath = "inbox/2026_04_15.md",
                            localContent = "alpha\nbeta",
                                incomingContent = "alpha\nbeta\ngamma",
                            isBinary = false,
                        ),
                            SyncReviewItem(
                            relativePath = "inbox/2026_04_16.md",
                            localContent = "start\nlocal only\nend",
                                incomingContent = "start\nremote only\nend",
                            isBinary = false,
                        ),
                    ),
                )
                val pendingReview = review.copy(items = listOf(review.items[1]))
                viewModel.showReviewDialog(review)
                syncProviders.getValue(SyncBackendType.INBOX).resolveResult =
                    UnifiedSyncResult.Review(
                        provider = SyncBackendType.INBOX,
                        message = "pending",
                        review = pendingReview,
                    )

                viewModel.autoResolveSafeConflicts()
                dispatcher.scheduler.advanceUntilIdle()

                operationLog shouldBe
                    listOf(
                        ConflictOperation.ResolveReview(
                            review = review,
                            resolution =
                                SyncReviewResolution(
                                    mapOf(
                                        "inbox/2026_04_15.md" to SyncReviewResolutionChoice.KEEP_INCOMING,
                                        "inbox/2026_04_16.md" to SyncReviewResolutionChoice.SKIP_FOR_NOW,
                                    ).toImmutableMap(),
                                ),
                        ),
                    )
                viewModel.state.value shouldBe SyncConflictDialogState.ReviewShowing(
                    reviewSession = pendingReview,
                    perItemChoices = mapOf(
                        "inbox/2026_04_16.md" to SyncReviewResolutionChoice.SKIP_FOR_NOW,
                    ).toImmutableMap(),
                    expandedFilePath = null,
                    isResolving = false,
                )
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

        test("applyResolution keeps dialog open with pending subset when conflicts remain") {
            runTest {
                val viewModel = createViewModel()
                val conflictSet = conflictSet(
                    source = SyncBackendType.S3,
                    files = listOf(
                        SyncConflictFile(
                            relativePath = "memos/2026_03_24.md",
                            localContent = "alpha\n\nbeta",
                            remoteContent = "alpha\n\nbeta\n\ngamma",
                            isBinary = false,
                        ),
                        SyncConflictFile(
                            relativePath = "images/photo.jpg",
                            localContent = null,
                            remoteContent = null,
                            isBinary = true,
                        ),
                    ),
                )
                val pending = conflictSet.copy(files = listOf(conflictSet.files[1]))
                viewModel.showConflictDialog(conflictSet)
                viewModel.setFileChoice("memos/2026_03_24.md", SyncConflictResolutionChoice.KEEP_REMOTE)
                viewModel.setFileChoice("images/photo.jpg", SyncConflictResolutionChoice.SKIP_FOR_NOW)
                syncProviders.getValue(SyncBackendType.S3).resolveResult =
                    UnifiedSyncResult.Conflict(
                        provider = SyncBackendType.S3,
                        message = "pending",
                        conflicts = pending,
                    )

                viewModel.applyResolution()
                dispatcher.scheduler.advanceUntilIdle()

                viewModel.state.value shouldBe SyncConflictDialogState.Showing(
                    conflictSet = pending,
                    perFileChoices = mapOf("images/photo.jpg" to SyncConflictResolutionChoice.SKIP_FOR_NOW).toImmutableMap(),
                    expandedFilePath = null,
                    isResolving = false,
                )
            }
        }
    }

    private fun createViewModel(): SyncConflictViewModel =
        SyncConflictViewModel(
            syncConflictResolutionUseCase =
                SyncConflictResolutionUseCase(
                    syncProviderRegistry = SyncProviderRegistry(syncProviders.values.toSet()),
                    memoRepository = com.lomo.app.testing.fakes.FakeMemoMutationRepository(memoRepository),
                ),
            syncReviewResolutionUseCase =
                SyncReviewResolutionUseCase(
                    syncProviderRegistry = SyncProviderRegistry(syncProviders.values.toSet()),
                ),
            backupSyncConflictFilesUseCase = BackupSyncConflictFilesUseCase(backupRepository),
        )

    private fun conflictSet(
        source: SyncBackendType = SyncBackendType.GIT,
        files: List<SyncConflictFile> =
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
            ),
    ): SyncConflictSet =
        SyncConflictSet(
            source = source,
            files = files,
            timestamp = 123L,
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

        data class Resolve(
            val conflictSet: SyncConflictSet,
            val resolution: SyncConflictResolution,
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
            resolution: SyncConflictResolution,
            conflictSet: SyncConflictSet,
        ): UnifiedSyncResult {
            operationLog +=
                ConflictOperation.Resolve(
                    conflictSet = conflictSet,
                    resolution = resolution,
                )
            return resolveResult
        }

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
}
