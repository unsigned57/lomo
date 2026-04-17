package com.lomo.domain.usecase

import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.UnifiedSyncOperation
import com.lomo.domain.model.UnifiedSyncResult
import com.lomo.domain.repository.AppVersionRepository
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.SyncInboxRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: StartupMaintenanceUseCase
 * - Behavior focus: recreating the sync-inbox directory structure on startup before normal inbox processing.
 * - Observable outcomes: ensureDirectoryStructure invocation and subsequent inbox sync trigger.
 * - Red phase: Fails before the fix because startup maintenance only processes the inbox and never recreates deleted memo/images/voice directories.
 * - Excludes: filesystem creation details inside the repository, cache warm-up behavior, and conflict dialog handling.
 */
class StartupMaintenanceSyncInboxStructureTest {
    @Test
    fun `runDeferredStartupTasks recreates sync inbox directories before processing inbox changes`() =
        runTest {
            val mediaRepository: MediaRepository = mockk(relaxed = true)
            val initializeWorkspaceUseCase: InitializeWorkspaceUseCase = mockk(relaxed = true)
            val syncAndRebuildUseCase: SyncAndRebuildUseCase = mockk(relaxed = true)
            val syncInboxRepository: SyncInboxRepository = mockk(relaxed = true)
            val appVersionRepository: AppVersionRepository = mockk(relaxed = true)
            val syncProviderRegistry =
                SyncProviderRegistry(
                    providers = listOf(InboxUnifiedSyncProvider(syncInboxRepository, mockk(relaxed = true))),
                )
            coEvery { syncInboxRepository.ensureDirectoryStructure() } returns Unit
            coEvery {
                syncInboxRepository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES)
            } returns UnifiedSyncResult.Success(SyncBackendType.INBOX, "processed")
            coEvery { appVersionRepository.getLastAppVersionOnce() } returns "1.0.0"

            val useCase =
                StartupMaintenanceUseCase(
                    mediaRepository = mediaRepository,
                    initializeWorkspaceUseCase = initializeWorkspaceUseCase,
                    syncAndRebuildUseCase = syncAndRebuildUseCase,
                    syncProviderRegistry = syncProviderRegistry,
                    appVersionRepository = appVersionRepository,
                    syncInboxRepository = syncInboxRepository,
                )

            useCase.runDeferredStartupTasks(rootDir = "/workspace", currentVersion = "1.0.0")

            coVerify(ordering = io.mockk.Ordering.SEQUENCE) {
                syncInboxRepository.ensureDirectoryStructure()
                syncInboxRepository.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES)
            }
        }
}
