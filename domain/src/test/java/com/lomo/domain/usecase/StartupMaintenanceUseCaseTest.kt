package com.lomo.domain.usecase

import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.AppVersionRepository
import com.lomo.domain.repository.MediaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: StartupMaintenanceUseCase
 * - Behavior focus: startup warm-up ordering, version-change resync gating, and best-effort failure handling after legacy recovery removal.
 * - Observable outcomes: sync trigger conditions, version persistence, refresh invocation counts, and root path mapping.
 * - Red phase: Fails before the fix because startup maintenance still depends on retired workspace recovery flows and invokes queries/restores that no longer exist.
 * - Excludes: repository implementation internals, filesystem or network integration, and UI rendering.
 */
class StartupMaintenanceUseCaseTest {
    private val mediaRepository: MediaRepository = mockk()
    private val initializeWorkspaceUseCase: InitializeWorkspaceUseCase = mockk()
    private val syncAndRebuildUseCase: SyncAndRebuildUseCase = mockk()
    private val appVersionRepository: AppVersionRepository = mockk()
    private val useCase =
        StartupMaintenanceUseCase(
            mediaRepository = mediaRepository,
            initializeWorkspaceUseCase = initializeWorkspaceUseCase,
            syncAndRebuildUseCase = syncAndRebuildUseCase,
            appVersionRepository = appVersionRepository,
        )

    @Test
    fun `initializeRootDirectory returns raw location from workspace use case`() =
        runTest {
            coEvery { initializeWorkspaceUseCase.currentRootLocation() } returns StorageLocation("/memo-root")

            val root = useCase.initializeRootDirectory()

            assertEquals("/memo-root", root)
        }

    @Test
    fun `runDeferredStartupTasks skips resync when version unchanged`() =
        runTest {
            coEvery { mediaRepository.refreshImageLocations() } returns Unit
            coEvery { appVersionRepository.getLastAppVersionOnce() } returns "0.9.1"

            useCase.runDeferredStartupTasks(rootDir = "/workspace", currentVersion = "0.9.1")

            coVerify(exactly = 1) { mediaRepository.refreshImageLocations() }
            coVerify(exactly = 0) { syncAndRebuildUseCase.invoke(any()) }
            coVerify(exactly = 0) { appVersionRepository.updateLastAppVersion(any()) }
        }

    @Test
    fun `runDeferredStartupTasks updates version without sync when root is missing`() =
        runTest {
            coEvery { mediaRepository.refreshImageLocations() } returns Unit
            coEvery { appVersionRepository.getLastAppVersionOnce() } returns "0.9.0"
            coEvery { appVersionRepository.updateLastAppVersion("0.9.1") } returns Unit

            useCase.runDeferredStartupTasks(rootDir = null, currentVersion = "0.9.1")

            coVerify(exactly = 1) { mediaRepository.refreshImageLocations() }
            coVerify(exactly = 0) { syncAndRebuildUseCase.invoke(any()) }
            coVerify(exactly = 1) { appVersionRepository.updateLastAppVersion("0.9.1") }
        }

    @Test
    fun `runDeferredStartupTasks keeps progressing when warm and resync rebuild fail`() =
        runTest {
            coEvery { appVersionRepository.getLastAppVersionOnce() } returns "0.9.0"
            coEvery { appVersionRepository.updateLastAppVersion("0.9.1") } returns Unit
            coEvery { syncAndRebuildUseCase.invoke(false) } throws IllegalStateException("sync failed")
            coEvery { mediaRepository.refreshImageLocations() } throws IllegalArgumentException("refresh failed")

            useCase.runDeferredStartupTasks(rootDir = "/workspace", currentVersion = "0.9.1")

            coVerify(exactly = 2) { mediaRepository.refreshImageLocations() }
            coVerify(exactly = 1) { syncAndRebuildUseCase.invoke(false) }
            coVerify(exactly = 1) { appVersionRepository.updateLastAppVersion("0.9.1") }
        }
}
