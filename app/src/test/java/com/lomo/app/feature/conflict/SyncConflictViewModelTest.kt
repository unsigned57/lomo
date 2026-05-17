package com.lomo.app.feature.conflict

import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictFileReviewState
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.usecase.BackupSyncConflictFilesUseCase
import com.lomo.domain.usecase.SyncConflictResolutionResult
import com.lomo.domain.usecase.SyncConflictResolutionUseCase
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: SyncConflictViewModel
 * - Behavior focus: dialog state transitions, source-aware default conflict choices, blocked review item handling, per-file choice updates, and apply-resolution success/failure handling.
 * - Observable outcomes: exposed dialog state, suggested per-file choices, resolving flag changes, and backup/resolve use-case invocation ordering.
 * - Red phase: Fails before the fix when S3 or WebDAV conflicts cannot surface suggested choices, initial-sync preview defaults, blocked inbox review items, or pending-conflict follow-up state.
 * - Excludes: Compose dialog rendering, repository internals, and sync engine implementation details.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncConflictViewModelTest : AppFunSpec() {
    private val dispatcher = StandardTestDispatcher()

    init {
        extension(MainDispatcherExtension(dispatcher))
    }

    private lateinit var syncConflictResolutionUseCase: SyncConflictResolutionUseCase
    private lateinit var backupSyncConflictFilesUseCase: BackupSyncConflictFilesUseCase

    init {
        beforeTest {
syncConflictResolutionUseCase = mockk(relaxed = true)
            backupSyncConflictFilesUseCase = mockk(relaxed = true)
        }
    }

    init {
        test("showConflictDialog exposes showing state with empty choices") {
            val viewModel = createViewModel()
            val conflictSet = conflictSet()

            viewModel.showConflictDialog(conflictSet)

            (viewModel.state.value) shouldBe (SyncConflictDialogState.Showing(
                    conflictSet = conflictSet,
                    perFileChoices = emptyMap<String, SyncConflictResolutionChoice>().toImmutableMap(),
                    expandedFilePath = null,
                    isResolving = false,
                ))
        }
    }

    init {
        test("showConflictDialog preselects remote when s3 remote content is a strict superset") {
            val viewModel = createViewModel()
            val conflictSet =
                conflictSet(
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

            viewModel.showConflictDialog(conflictSet)

            (viewModel.state.value) shouldBe (SyncConflictDialogState.Showing(
                    conflictSet = conflictSet,
                    perFileChoices =
                        mapOf(
                            "memos/2026_03_24.md" to SyncConflictResolutionChoice.KEEP_REMOTE,
                        ).toImmutableMap(),
                    expandedFilePath = null,
                    isResolving = false,
                ))
        }
    }

    init {
        test("showConflictDialog preselects merge when s3 text inserts do not overlap") {
            val viewModel = createViewModel()
            val conflictSet =
                conflictSet(
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

            viewModel.showConflictDialog(conflictSet)

            (viewModel.state.value) shouldBe (SyncConflictDialogState.Showing(
                    conflictSet = conflictSet,
                    perFileChoices =
                        mapOf(
                            "memos/2026_03_24.md" to SyncConflictResolutionChoice.MERGE_TEXT,
                        ).toImmutableMap(),
                    expandedFilePath = null,
                    isResolving = false,
                ))
        }
    }

    init {
        test("showConflictDialog preselects merge for inbox short disjoint memo content") {
            val viewModel = createViewModel()
            val conflictSet =
                conflictSet(
                    source = SyncBackendType.INBOX,
                    files =
                        listOf(
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

            (viewModel.state.value) shouldBe (SyncConflictDialogState.Showing(
                    conflictSet = conflictSet,
                    perFileChoices =
                        mapOf(
                            "inbox/2026_04_15.md" to SyncConflictResolutionChoice.MERGE_TEXT,
                        ).toImmutableMap(),
                    expandedFilePath = null,
                    isResolving = false,
                ))
        }
    }

    init {
        test("showConflictDialog leaves blocked inbox review item unselected") {
            val viewModel = createViewModel()
            val conflictSet =
                conflictSet(
                    source = SyncBackendType.INBOX,
                    files =
                        listOf(
                            SyncConflictFile(
                                relativePath = "inbox/2026_04_16.md",
                                localContent = null,
                                remoteContent = "memo with image ![cover](cover.png)",
                                isBinary = false,
                                reviewState = SyncConflictFileReviewState.BLOCKED,
                                reviewMessage = "Missing attachments: cover.png",
                            ),
                        ),
                )

            viewModel.showConflictDialog(conflictSet)

            (viewModel.state.value) shouldBe (SyncConflictDialogState.Showing(
                    conflictSet = conflictSet,
                    perFileChoices = emptyMap<String, SyncConflictResolutionChoice>().toImmutableMap(),
                    expandedFilePath = null,
                    isResolving = false,
                ))
        }
    }

    init {
        test("showConflictDialog preselects local when local metadata is much newer for identical content") {
            val viewModel = createViewModel()
            val conflictSet =
                conflictSet(
                    source = SyncBackendType.S3,
                    files =
                        listOf(
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

            (viewModel.state.value) shouldBe (SyncConflictDialogState.Showing(
                    conflictSet = conflictSet,
                    perFileChoices =
                        mapOf(
                            "memos/2026_03_24.md" to SyncConflictResolutionChoice.KEEP_LOCAL,
                        ).toImmutableMap(),
                    expandedFilePath = null,
                    isResolving = false,
                ))
        }
    }

    init {
        test("showConflictDialog preselects the non-empty side when the remote content was deleted") {
            val viewModel = createViewModel()
            val conflictSet =
                conflictSet(
                    source = SyncBackendType.WEBDAV,
                    files =
                        listOf(
                            SyncConflictFile(
                                relativePath = "memos/2026_03_24.md",
                                localContent = "keep local",
                                remoteContent = "",
                                isBinary = false,
                            ),
                        ),
                )

            viewModel.showConflictDialog(conflictSet)

            (viewModel.state.value) shouldBe (SyncConflictDialogState.Showing(
                    conflictSet = conflictSet,
                    perFileChoices =
                        mapOf(
                            "memos/2026_03_24.md" to SyncConflictResolutionChoice.KEEP_LOCAL,
                        ).toImmutableMap(),
                    expandedFilePath = null,
                    isResolving = false,
                ))
        }
    }

    init {
        test("setAllChoices fills every file choice in showing state") {
            val viewModel = createViewModel()
            val conflictSet = conflictSet()
            viewModel.showConflictDialog(conflictSet)

            viewModel.setAllChoices(SyncConflictResolutionChoice.KEEP_REMOTE)

            val state = viewModel.state.value as SyncConflictDialogState.Showing
            (state.perFileChoices) shouldBe (mapOf(
                    "memos/2026_03_24.md" to SyncConflictResolutionChoice.KEEP_REMOTE,
                    "images/photo.jpg" to SyncConflictResolutionChoice.KEEP_REMOTE,
                ).toImmutableMap())
        }
    }

    init {
        test("acceptSuggestedChoices applies initial sync preview defaults without touching unsupported files") {
            val viewModel = createViewModel()
            val conflictSet =
                conflictSet(
                    source = SyncBackendType.S3,
                    files =
                        listOf(
                            SyncConflictFile(
                                relativePath = "memos/2026_03_24.md",
                                localContent = "alpha\n\nbeta",
                                remoteContent = "alpha\n\nbeta\n\ngamma",
                                isBinary = false,
                            ),
                            SyncConflictFile(
                                relativePath = "memos/2026_03_25.md",
                                localContent = "start\nlocal\nmiddle\nend",
                                remoteContent = "start\nmiddle\nremote\nend",
                                isBinary = false,
                            ),
                            SyncConflictFile(
                                relativePath = "images/photo.jpg",
                                localContent = null,
                                remoteContent = null,
                                isBinary = true,
                            ),
                        ),
                    sessionKind = com.lomo.domain.model.SyncConflictSessionKind.INITIAL_SYNC_PREVIEW,
                )
            viewModel.showConflictDialog(conflictSet)
            viewModel.setFileChoice("images/photo.jpg", SyncConflictResolutionChoice.SKIP_FOR_NOW)

            viewModel.acceptSuggestedChoices()

            val state = viewModel.state.value as SyncConflictDialogState.Showing
            (state.perFileChoices) shouldBe (mapOf(
                    "memos/2026_03_24.md" to SyncConflictResolutionChoice.KEEP_REMOTE,
                    "memos/2026_03_25.md" to SyncConflictResolutionChoice.MERGE_TEXT,
                    "images/photo.jpg" to SyncConflictResolutionChoice.SKIP_FOR_NOW,
                ).toImmutableMap())
        }
    }

    init {
        test("toggleExpandedFile toggles currently expanded path") {
            val viewModel = createViewModel()
            val conflictSet = conflictSet()
            viewModel.showConflictDialog(conflictSet)

            viewModel.toggleExpandedFile("memos/2026_03_24.md")
            ((viewModel.state.value as SyncConflictDialogState.Showing).expandedFilePath) shouldBe ("memos/2026_03_24.md")

            viewModel.toggleExpandedFile("memos/2026_03_24.md")
            ((viewModel.state.value as SyncConflictDialogState.Showing).expandedFilePath) shouldBe (null)
        }
    }

    init {
        test("applyResolution backs up files and hides dialog on success") {
            runTest {
                val viewModel = createViewModel()
                val conflictSet = conflictSet()
                viewModel.showConflictDialog(conflictSet)
                viewModel.setFileChoice("memos/2026_03_24.md", SyncConflictResolutionChoice.KEEP_LOCAL)
                viewModel.setFileChoice("images/photo.jpg", SyncConflictResolutionChoice.KEEP_REMOTE)
                coEvery { backupSyncConflictFilesUseCase.invoke(any(), any()) } returns Unit
                coEvery { syncConflictResolutionUseCase.resolve(any(), any()) } returns SyncConflictResolutionResult.Resolved

                viewModel.applyResolution()
                dispatcher.scheduler.advanceUntilIdle()

                (viewModel.state.value) shouldBe (SyncConflictDialogState.Hidden)
                coVerifyOrder {
                    backupSyncConflictFilesUseCase.invoke(conflictSet.files, any())
                    syncConflictResolutionUseCase.resolve(
                        conflictSet = conflictSet,
                        resolution =
                            SyncConflictResolution(
                                mapOf(
                                    "memos/2026_03_24.md" to SyncConflictResolutionChoice.KEEP_LOCAL,
                                    "images/photo.jpg" to SyncConflictResolutionChoice.KEEP_REMOTE,
                                ).toImmutableMap(),
                            ),
                    )
                }
            }
        }
    }

    init {
        test("applyResolution keeps dialog open and clears resolving after failure") {
            runTest {
                val viewModel = createViewModel()
                val conflictSet = conflictSet()
                viewModel.showConflictDialog(conflictSet)
                viewModel.setAllChoices(SyncConflictResolutionChoice.KEEP_LOCAL)
                coEvery { backupSyncConflictFilesUseCase.invoke(any(), any()) } throws IllegalStateException("backup failed")

                viewModel.applyResolution()

                val resolvingState = viewModel.state.value as SyncConflictDialogState.Showing
                ((resolvingState.isResolving)) shouldBe true

                dispatcher.scheduler.advanceUntilIdle()

                val state = viewModel.state.value as SyncConflictDialogState.Showing
                (state.conflictSet) shouldBe (conflictSet)
                (state.isResolving) shouldBe (false)
                (state.perFileChoices) shouldBe (mapOf(
                        "memos/2026_03_24.md" to SyncConflictResolutionChoice.KEEP_LOCAL,
                        "images/photo.jpg" to SyncConflictResolutionChoice.KEEP_LOCAL,
                    ).toImmutableMap())
                coVerify(exactly = 0) { syncConflictResolutionUseCase.resolve(any(), any()) }
            }
        }
    }

    init {
        test("autoResolveSafeConflicts applies safe choices and defers unresolved s3 files") {
            runTest {
                val viewModel = createViewModel()
                val conflictSet =
                    conflictSet(
                        source = SyncBackendType.S3,
                        files =
                            listOf(
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
                coEvery { backupSyncConflictFilesUseCase.invoke(any(), any()) } returns Unit
                coEvery { syncConflictResolutionUseCase.resolve(any(), any()) } returns SyncConflictResolutionResult.Pending(pending)

                viewModel.autoResolveSafeConflicts()
                dispatcher.scheduler.advanceUntilIdle()

                coVerifyOrder {
                    backupSyncConflictFilesUseCase.invoke(listOf(conflictSet.files.first()), any())
                    syncConflictResolutionUseCase.resolve(
                        conflictSet = conflictSet,
                        resolution =
                            SyncConflictResolution(
                                mapOf(
                                    "memos/2026_03_24.md" to SyncConflictResolutionChoice.KEEP_REMOTE,
                                    "memos/2026_03_25.md" to SyncConflictResolutionChoice.SKIP_FOR_NOW,
                                ).toImmutableMap(),
                            ),
                    )
                }
                (viewModel.state.value) shouldBe (SyncConflictDialogState.Showing(
                        conflictSet = pending,
                        perFileChoices =
                            mapOf(
                                "memos/2026_03_25.md" to SyncConflictResolutionChoice.SKIP_FOR_NOW,
                            ).toImmutableMap(),
                        expandedFilePath = null,
                        isResolving = false,
                    ))
            }
        }
    }

    init {
        test("autoResolveSafeConflicts applies safe choices and defers unresolved inbox files") {
            runTest {
                val viewModel = createViewModel()
                val conflictSet =
                    conflictSet(
                        source = SyncBackendType.INBOX,
                        files =
                            listOf(
                                SyncConflictFile(
                                    relativePath = "inbox/2026_04_15.md",
                                    localContent = "alpha\nbeta",
                                    remoteContent = "alpha\nbeta\ngamma",
                                    isBinary = false,
                                ),
                                SyncConflictFile(
                                    relativePath = "inbox/2026_04_16.md",
                                    localContent = "start\nlocal only\nend",
                                    remoteContent = "start\nremote only\nend",
                                    isBinary = false,
                                ),
                            ),
                    )
                val pending = conflictSet.copy(files = listOf(conflictSet.files[1]))
                viewModel.showConflictDialog(conflictSet)
                coEvery { backupSyncConflictFilesUseCase.invoke(any(), any()) } returns Unit
                coEvery { syncConflictResolutionUseCase.resolve(any(), any()) } returns SyncConflictResolutionResult.Pending(pending)

                viewModel.autoResolveSafeConflicts()
                dispatcher.scheduler.advanceUntilIdle()

                coVerifyOrder {
                    backupSyncConflictFilesUseCase.invoke(listOf(conflictSet.files.first()), any())
                    syncConflictResolutionUseCase.resolve(
                        conflictSet = conflictSet,
                        resolution =
                            SyncConflictResolution(
                                mapOf(
                                    "inbox/2026_04_15.md" to SyncConflictResolutionChoice.KEEP_REMOTE,
                                    "inbox/2026_04_16.md" to SyncConflictResolutionChoice.SKIP_FOR_NOW,
                                ).toImmutableMap(),
                            ),
                    )
                }
                (viewModel.state.value) shouldBe (SyncConflictDialogState.Showing(
                        conflictSet = pending,
                        perFileChoices =
                            mapOf(
                                "inbox/2026_04_16.md" to SyncConflictResolutionChoice.SKIP_FOR_NOW,
                            ).toImmutableMap(),
                        expandedFilePath = null,
                        isResolving = false,
                    ))
            }
        }
    }

    init {
        test("applyResolution does nothing when dialog is hidden") {
            runTest {
                val viewModel = createViewModel()

                viewModel.applyResolution()
                advanceUntilIdle()

                (viewModel.state.value) shouldBe (SyncConflictDialogState.Hidden)
                coVerify(exactly = 0) { backupSyncConflictFilesUseCase.invoke(any(), any()) }
                coVerify(exactly = 0) { syncConflictResolutionUseCase.resolve(any(), any()) }
            }
        }
    }

    init {
        test("applyResolution keeps dialog open with pending subset when conflicts remain") {
            runTest {
                val viewModel = createViewModel()
                val conflictSet =
                    conflictSet(
                        source = SyncBackendType.S3,
                        files =
                            listOf(
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
                coEvery { backupSyncConflictFilesUseCase.invoke(any(), any()) } returns Unit
                coEvery { syncConflictResolutionUseCase.resolve(any(), any()) } returns SyncConflictResolutionResult.Pending(pending)

                viewModel.applyResolution()
                dispatcher.scheduler.advanceUntilIdle()

                (viewModel.state.value) shouldBe (SyncConflictDialogState.Showing(
                        conflictSet = pending,
                        perFileChoices =
                            mapOf("images/photo.jpg" to SyncConflictResolutionChoice.SKIP_FOR_NOW).toImmutableMap(),
                        expandedFilePath = null,
                        isResolving = false,
                    ))
            }
        }
    }

    private fun createViewModel(): SyncConflictViewModel =
        SyncConflictViewModel(
            syncConflictResolutionUseCase = syncConflictResolutionUseCase,
            backupSyncConflictFilesUseCase = backupSyncConflictFilesUseCase,
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
        sessionKind: com.lomo.domain.model.SyncConflictSessionKind =
            com.lomo.domain.model.SyncConflictSessionKind.STANDARD_CONFLICT,
    ): SyncConflictSet =
        SyncConflictSet(
            source = source,
            files = files,
            timestamp = 123L,
            sessionKind = sessionKind,
        )
}
