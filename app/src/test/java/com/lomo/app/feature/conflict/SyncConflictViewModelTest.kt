package com.lomo.app.feature.conflict

import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.usecase.BackupSyncConflictFilesUseCase
import com.lomo.domain.usecase.SyncConflictResolutionUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: SyncConflictViewModel
 * - Behavior focus: dialog state transitions, per-file choice updates, and apply-resolution success/failure handling.
 * - Observable outcomes: exposed dialog state, resolving flag changes, and backup/resolve use-case invocation ordering.
 * - Excludes: Compose dialog rendering, repository internals, and sync engine implementation details.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncConflictViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var syncConflictResolutionUseCase: SyncConflictResolutionUseCase
    private lateinit var backupSyncConflictFilesUseCase: BackupSyncConflictFilesUseCase

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        syncConflictResolutionUseCase = mockk(relaxed = true)
        backupSyncConflictFilesUseCase = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `showConflictDialog exposes showing state with empty choices`() {
        val viewModel = createViewModel()
        val conflictSet = conflictSet()

        viewModel.showConflictDialog(conflictSet)

        assertEquals(
            SyncConflictDialogState.Showing(
                conflictSet = conflictSet,
                perFileChoices = emptyMap(),
                expandedFilePath = null,
                isResolving = false,
            ),
            viewModel.state.value,
        )
    }

    @Test
    fun `setAllChoices fills every file choice in showing state`() {
        val viewModel = createViewModel()
        val conflictSet = conflictSet()
        viewModel.showConflictDialog(conflictSet)

        viewModel.setAllChoices(SyncConflictResolutionChoice.KEEP_REMOTE)

        val state = viewModel.state.value as SyncConflictDialogState.Showing
        assertEquals(
            mapOf(
                "memos/2026_03_24.md" to SyncConflictResolutionChoice.KEEP_REMOTE,
                "images/photo.jpg" to SyncConflictResolutionChoice.KEEP_REMOTE,
            ),
            state.perFileChoices,
        )
    }

    @Test
    fun `toggleExpandedFile toggles currently expanded path`() {
        val viewModel = createViewModel()
        val conflictSet = conflictSet()
        viewModel.showConflictDialog(conflictSet)

        viewModel.toggleExpandedFile("memos/2026_03_24.md")
        assertEquals(
            "memos/2026_03_24.md",
            (viewModel.state.value as SyncConflictDialogState.Showing).expandedFilePath,
        )

        viewModel.toggleExpandedFile("memos/2026_03_24.md")
        assertEquals(
            null,
            (viewModel.state.value as SyncConflictDialogState.Showing).expandedFilePath,
        )
    }

    @Test
    fun `applyResolution backs up files and hides dialog on success`() =
        runTest {
            val viewModel = createViewModel()
            val conflictSet = conflictSet()
            viewModel.showConflictDialog(conflictSet)
            viewModel.setFileChoice("memos/2026_03_24.md", SyncConflictResolutionChoice.KEEP_LOCAL)
            viewModel.setFileChoice("images/photo.jpg", SyncConflictResolutionChoice.KEEP_REMOTE)
            coEvery { backupSyncConflictFilesUseCase.invoke(any(), any()) } returns Unit
            coEvery { syncConflictResolutionUseCase.resolve(any(), any()) } returns Unit

            viewModel.applyResolution()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(SyncConflictDialogState.Hidden, viewModel.state.value)
            coVerifyOrder {
                backupSyncConflictFilesUseCase.invoke(conflictSet.files, any())
                syncConflictResolutionUseCase.resolve(
                    conflictSet = conflictSet,
                    resolution =
                        SyncConflictResolution(
                            mapOf(
                                "memos/2026_03_24.md" to SyncConflictResolutionChoice.KEEP_LOCAL,
                                "images/photo.jpg" to SyncConflictResolutionChoice.KEEP_REMOTE,
                            ),
                        ),
                )
            }
        }

    @Test
    fun `applyResolution keeps dialog open and clears resolving after failure`() =
        runTest {
            val viewModel = createViewModel()
            val conflictSet = conflictSet()
            viewModel.showConflictDialog(conflictSet)
            viewModel.setAllChoices(SyncConflictResolutionChoice.KEEP_LOCAL)
            coEvery { backupSyncConflictFilesUseCase.invoke(any(), any()) } throws IllegalStateException("backup failed")

            viewModel.applyResolution()

            val resolvingState = viewModel.state.value as SyncConflictDialogState.Showing
            assertTrue(resolvingState.isResolving)

            dispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.state.value as SyncConflictDialogState.Showing
            assertEquals(conflictSet, state.conflictSet)
            assertEquals(false, state.isResolving)
            assertEquals(
                mapOf(
                    "memos/2026_03_24.md" to SyncConflictResolutionChoice.KEEP_LOCAL,
                    "images/photo.jpg" to SyncConflictResolutionChoice.KEEP_LOCAL,
                ),
                state.perFileChoices,
            )
            coVerify(exactly = 0) { syncConflictResolutionUseCase.resolve(any(), any()) }
        }

    @Test
    fun `applyResolution does nothing when dialog is hidden`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.applyResolution()
            advanceUntilIdle()

            assertEquals(SyncConflictDialogState.Hidden, viewModel.state.value)
            coVerify(exactly = 0) { backupSyncConflictFilesUseCase.invoke(any(), any()) }
            coVerify(exactly = 0) { syncConflictResolutionUseCase.resolve(any(), any()) }
        }

    private fun createViewModel(): SyncConflictViewModel =
        SyncConflictViewModel(
            syncConflictResolutionUseCase = syncConflictResolutionUseCase,
            backupSyncConflictFilesUseCase = backupSyncConflictFilesUseCase,
        )

    private fun conflictSet(): SyncConflictSet =
        SyncConflictSet(
            source = SyncBackendType.GIT,
            files =
                listOf(
                    SyncConflictFile(
                        relativePath = "memos/2026_03_24.md",
                        localContent = "local memo",
                        remoteContent = "remote memo",
                        isBinary = false,
                    ),
                    SyncConflictFile(
                        relativePath = "images/photo.jpg",
                        localContent = null,
                        remoteContent = null,
                        isBinary = true,
                    ),
                ),
            timestamp = 123L,
        )
}
