package com.lomo.domain.usecase

import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.UnifiedSyncOperation
import com.lomo.domain.model.UnifiedSyncResult
import com.lomo.domain.repository.AppVersionRepository
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.PreferencesRepository
import com.lomo.domain.repository.SyncInboxRepository
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: StartupMaintenanceUseCase
 * - Behavior focus: deferred startup ordering, guaranteed sync-inbox directory preparation,
 *   cache-backed image-map warm-up, version-change resync gating, and best-effort failure handling.
 * - Observable outcomes: image-warm invocation/order, sync trigger conditions, version persistence,
 *   refresh invocation counts, and root path mapping.
 * - Red phase: Fails before the startup-performance fix because deferred startup skips image-map
 *   warm-up when the app version is unchanged and does not start inbox processing concurrently.
 * - Excludes: repository implementation internals, filesystem or network integration, and UI rendering.
 *
 * Test Change Justification:
 * - Reason category: Product startup-performance contract changed.
 * - Old behavior/assertion being replaced: deferred startup processed inbox work before image-map
 *   warm-up and only rebuilt image locations as a version-change side effect.
 * - Why old assertion is no longer correct: the startup contract now treats the persisted image map
 *   as the primary UI dependency, so deferred startup always warms it first and keeps inbox/rebuild
 *   work behind that hot path.
 * - Coverage preserved by: sync-inbox preparation/processing, version persistence, conflict propagation,
 *   and version-change rebuild behavior remain asserted.
 * - Why this is not fitting the test to the implementation: the new assertions encode the requested
 *   cold-start performance contract before production edits.
 */
class StartupMaintenanceUseCaseTest : DomainFunSpec() {
    private val mediaRepository: MediaRepository = mockk()
    private val initializeWorkspaceUseCase: InitializeWorkspaceUseCase = mockk()
    private val syncAndRebuildUseCase: SyncAndRebuildUseCase = mockk()
    private val syncInboxRepository: SyncInboxRepository = mockk()
    private val preferencesRepository: PreferencesRepository = mockk(relaxed = true)
    private val appVersionRepository: AppVersionRepository = mockk()
    private val syncProviderRegistry =
        SyncProviderRegistry(
            providers =
                listOf(
                    InboxUnifiedSyncProvider(
                        syncInboxRepository = syncInboxRepository,
                        preferencesRepository = preferencesRepository,
                    ),
                ),
        )
    private val useCase =
        StartupMaintenanceUseCase(
            mediaRepository = mediaRepository,
            initializeWorkspaceUseCase = initializeWorkspaceUseCase,
            syncAndRebuildUseCase = syncAndRebuildUseCase,
            syncProviderRegistry = syncProviderRegistry,
            appVersionRepository = appVersionRepository,
            syncInboxRepository = syncInboxRepository,
        )
    init {
        test("initializeRootDirectory returns raw location from workspace use case") {
            runTest {
                        coEvery { initializeWorkspaceUseCase.currentRootLocation() } returns StorageLocation("/memo-root")

                        val root = useCase.initializeRootDirectory()

                        root shouldBe "/memo-root"
                    }
        }
    }
    init {
        test("runDeferredStartupTasks warms image cache and skips resync when version unchanged") {
            runTest {
                        every { preferencesRepository.isSyncInboxEnabled() } returns flowOf(true)
                        coEvery { syncInboxRepository.ensureDirectoryStructure() } returns Unit
                        coEvery {
                            syncInboxRepository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES)
                        } returns UnifiedSyncResult.Success(SyncBackendType.INBOX, "processed")
                        coEvery { mediaRepository.refreshImageLocations() } returns Unit
                        coEvery { appVersionRepository.getLastAppVersionOnce() } returns "0.9.1"

                        useCase.runDeferredStartupTasks(rootDir = "/workspace", currentVersion = "0.9.1")

                        coVerify(exactly = 1) { syncInboxRepository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES) }
                        coVerify(exactly = 1) { mediaRepository.refreshImageLocations() }
                        coVerify(exactly = 0) { syncAndRebuildUseCase.invoke(any()) }
                        coVerify(exactly = 0) { appVersionRepository.updateLastAppVersion(any()) }
                    }
        }
    }
    init {
        test("runDeferredStartupTasks recreates sync inbox directories before processing inbox changes") {
            runTest {
                        every { preferencesRepository.isSyncInboxEnabled() } returns flowOf(true)
                        coEvery { syncInboxRepository.ensureDirectoryStructure() } returns Unit
                        coEvery {
                            syncInboxRepository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES)
                        } returns UnifiedSyncResult.Success(SyncBackendType.INBOX, "processed")
                        coEvery { mediaRepository.refreshImageLocations() } returns Unit
                        coEvery { appVersionRepository.getLastAppVersionOnce() } returns "0.9.1"

                        useCase.runDeferredStartupTasks(rootDir = "/workspace", currentVersion = "0.9.1")

                        coVerify(ordering = io.mockk.Ordering.SEQUENCE) {
                            syncInboxRepository.ensureDirectoryStructure()
                            syncInboxRepository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES)
                        }
                    }
        }
    }
    init {
        test("runDeferredStartupTasks updates version without rebuild when root is missing") {
            runTest {
                        every { preferencesRepository.isSyncInboxEnabled() } returns flowOf(true)
                        coEvery { syncInboxRepository.ensureDirectoryStructure() } returns Unit
                        coEvery {
                            syncInboxRepository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES)
                        } returns UnifiedSyncResult.Success(SyncBackendType.INBOX, "processed")
                        coEvery { mediaRepository.refreshImageLocations() } returns Unit
                        coEvery { appVersionRepository.getLastAppVersionOnce() } returns "0.9.0"
                        coEvery { appVersionRepository.updateLastAppVersion("0.9.1") } returns Unit

                        useCase.runDeferredStartupTasks(rootDir = null, currentVersion = "0.9.1")

                        coVerify(exactly = 1) { syncInboxRepository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES) }
                        coVerify(exactly = 1) { mediaRepository.refreshImageLocations() }
                        coVerify(exactly = 0) { syncAndRebuildUseCase.invoke(any()) }
                        coVerify(exactly = 1) { appVersionRepository.updateLastAppVersion("0.9.1") }
                    }
        }
    }
    init {
        test("runDeferredStartupTasks starts inbox processing while image warm up is still running") {
            runTest {
                        val imageWarmStarted = CompletableDeferred<Unit>()
                        val releaseImageWarm = CompletableDeferred<Unit>()
                        every { preferencesRepository.isSyncInboxEnabled() } returns flowOf(true)
                        coEvery { syncInboxRepository.ensureDirectoryStructure() } returns Unit
                        coEvery {
                            syncInboxRepository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES)
                        } returns UnifiedSyncResult.Success(SyncBackendType.INBOX, "processed")
                        coEvery { mediaRepository.refreshImageLocations() } coAnswers {
                            imageWarmStarted.complete(Unit)
                            releaseImageWarm.await()
                        }
                        coEvery { appVersionRepository.getLastAppVersionOnce() } returns "0.9.1"

                        val deferredStartup = async {
                            useCase.runDeferredStartupTasks(rootDir = "/workspace", currentVersion = "0.9.1")
                        }

                        imageWarmStarted.await()
                        coVerify(exactly = 1) { syncInboxRepository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES) }

                        releaseImageWarm.complete(Unit)
                        deferredStartup.await()
                    }
        }
    }
    init {
        test("runDeferredStartupTasks keeps progressing when version-change rebuild and image refresh fail") {
            runTest {
                        every { preferencesRepository.isSyncInboxEnabled() } returns flowOf(true)
                        coEvery { syncInboxRepository.ensureDirectoryStructure() } returns Unit
                        coEvery {
                            syncInboxRepository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES)
                        } returns UnifiedSyncResult.Success(SyncBackendType.INBOX, "processed")
                        coEvery { appVersionRepository.getLastAppVersionOnce() } returns "0.9.0"
                        coEvery { appVersionRepository.updateLastAppVersion("0.9.1") } returns Unit
                        coEvery { syncAndRebuildUseCase.invoke(false) } throws IllegalStateException("sync failed")
                        coEvery { mediaRepository.refreshImageLocations() } throws IllegalArgumentException("refresh failed")

                        useCase.runDeferredStartupTasks(rootDir = "/workspace", currentVersion = "0.9.1")

                        coVerify(exactly = 1) { syncInboxRepository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES) }
                        coVerify(exactly = 2) { mediaRepository.refreshImageLocations() }
                        coVerify(exactly = 1) { syncAndRebuildUseCase.invoke(false) }
                        coVerify(exactly = 1) { appVersionRepository.updateLastAppVersion("0.9.1") }
                    }
        }
    }
    init {
        test("runDeferredStartupTasks keeps progressing when sync inbox import fails generically") {
            runTest {
                        every { preferencesRepository.isSyncInboxEnabled() } returns flowOf(true)
                        coEvery { syncInboxRepository.ensureDirectoryStructure() } returns Unit
                        coEvery {
                            syncInboxRepository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES)
                        } throws IllegalStateException("inbox failed")
                        coEvery { mediaRepository.refreshImageLocations() } returns Unit
                        coEvery { appVersionRepository.getLastAppVersionOnce() } returns "0.9.1"

                        useCase.runDeferredStartupTasks(rootDir = "/workspace", currentVersion = "0.9.1")

                        coVerify(exactly = 1) { syncInboxRepository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES) }
                        coVerify(exactly = 1) { mediaRepository.refreshImageLocations() }
                        coVerify(exactly = 0) { syncAndRebuildUseCase.invoke(any()) }
                    }
        }
    }
    init {
        test("runDeferredStartupTasks rethrows sync inbox conflicts for UI handling") {
            runTest {
                        val conflicts =
                            SyncConflictSet(
                                source = SyncBackendType.INBOX,
                                files =
                                    listOf(
                                        SyncConflictFile(
                                            relativePath = "inbox/2026_04_13.md",
                                            localContent = "local",
                                            remoteContent = "remote",
                                            isBinary = false,
                                        ),
                                    ),
                                timestamp = 123L,
                            )
                        val exception = SyncConflictException(conflicts)
                        every { preferencesRepository.isSyncInboxEnabled() } returns flowOf(true)
                        coEvery { syncInboxRepository.ensureDirectoryStructure() } returns Unit
                        coEvery {
                            syncInboxRepository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES)
                        } throws exception

                        val thrown =
                            kotlin.runCatching {
                                useCase.runDeferredStartupTasks(rootDir = "/workspace", currentVersion = "0.9.1")
                            }.exceptionOrNull()

                        thrown shouldBe exception
                        coVerify(exactly = 1) { syncInboxRepository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES) }
                        coVerify(exactly = 1) { mediaRepository.refreshImageLocations() }
                    }
        }
    }
}
