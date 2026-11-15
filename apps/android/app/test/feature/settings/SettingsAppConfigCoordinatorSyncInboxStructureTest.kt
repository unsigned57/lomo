package com.lomo.app.feature.settings
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.app.testing.fakes.FakeCustomFontStore
import com.lomo.app.testing.fakes.FakeMemoSnapshotPreferencesRepository
import com.lomo.app.testing.fakes.FakeWorkspaceMutationLease
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.UnifiedSyncOperation
import com.lomo.domain.model.UnifiedSyncResult
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.repository.SyncInboxRepository
import com.lomo.domain.repository.WorkspaceStateResolver
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Capability: Settings app config coordination and sync-inbox subdirectory creation structure.
 * Scenarios:
 * - Given a selected sync-inbox directory or URI, when the coordinator applies it, then the repository stores
 *   the selected location and ensures the required directory structure.
 *
 * - Observable outcomes:
 *   - AppConfigRepository applied locations for SYNC_INBOX.
 *   - SyncInboxRepository ensureDirectoryStructure invocation count.
 * - TDD proof: Ensures coordinator applies storage area updates and runs directory structural setup.
 * - Excludes: DataStore persistence internals, Compose UI.
 *
 * Test Change Justification:
 * Reason category: Mechanical formatting and import alignment for
 * SettingsAppConfigCoordinatorSyncInboxStructure.
 * Old behavior/assertion being replaced: None; existing scenario bodies and expected results are retained.
 * Why old assertion is no longer correct: Existing assertions remain correct. Layout, imports, and equivalent
 * fixture declaration syntax do not change their behavior.
 * Coverage preserved by: The existing scenarios, including updating sync inbox location ensures the required
 * directory structure.
 * Why this is not fitting the test to the implementation: No expected result is relaxed or replaced; the
 * original behavior locks remain executable.
 */
class SettingsAppConfigCoordinatorSyncInboxStructureTest : AppFunSpec() {
    private val appConfigRepository = FakeAppConfigRepository()
    private val workspaceStateResolver = FakeWorkspaceStateResolver()
    private val switchRootStorageUseCase = SwitchRootStorageUseCase(
        appConfigRepository,
        workspaceStateResolver,
        FakeWorkspaceMutationLease(),
        com.lomo.app.testing.fakes.FakeEngineReadinessRepository(),
    )
    private val syncInboxRepository = FakeSyncInboxRepository()

    private class FakeWorkspaceStateResolver : WorkspaceStateResolver {
        override suspend fun rebuildFromCurrentWorkspace() {}
    }

    private class FakeSyncInboxRepository : SyncInboxRepository {
        var ensureDirectoryStructureCallCount = 0

        override fun syncState(): Flow<UnifiedSyncState> {
            TODO("Not needed for coordinator test")
        }

        override suspend fun ensureDirectoryStructure() {
            ensureDirectoryStructureCallCount++
        }

        override suspend fun sync(operation: UnifiedSyncOperation): UnifiedSyncResult {
            TODO("Not needed for coordinator test")
        }

        override suspend fun resolveReview(
            resolution: com.lomo.domain.model.SyncReviewResolution,
            review: com.lomo.domain.model.SyncReviewSession,
        ): UnifiedSyncResult {
            TODO("Not needed for coordinator test")
        }
    }

    init {
        test("updating sync inbox location ensures the required directory structure") {
            runTest {
                val coordinator = SettingsAppConfigCoordinator(
                    appConfigRepository = appConfigRepository,
                    switchRootStorageUseCase = switchRootStorageUseCase,
                    scope = backgroundScope,
                    customFontStore = FakeCustomFontStore(),
                    memoSnapshotPreferencesRepository = FakeMemoSnapshotPreferencesRepository(),
                    syncInboxRepository = syncInboxRepository,
                )

                coordinator.updateSyncInboxDirectory("/sync-inbox")
                appConfigRepository.currentLocation(StorageArea.SYNC_INBOX) shouldBe StorageLocation("/sync-inbox")

                coordinator.updateSyncInboxUri("content://tree/sync-inbox")
                appConfigRepository.currentLocation(
                    StorageArea.SYNC_INBOX,
                ) shouldBe StorageLocation("content://tree/sync-inbox")

                syncInboxRepository.ensureDirectoryStructureCallCount shouldBe 2
            }
        }
    }
}
