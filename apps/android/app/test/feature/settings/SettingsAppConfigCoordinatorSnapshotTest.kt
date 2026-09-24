package com.lomo.app.feature.settings
import com.lomo.app.testing.fakes.FakeWorkspaceMutationLease

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.app.testing.fakes.FakeCustomFontStore
import com.lomo.app.testing.fakes.FakeMemoSnapshotPreferencesRepository
import com.lomo.app.testing.fakes.FakeSyncInboxRepository
import com.lomo.domain.repository.WorkspaceStateResolver
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Capability: Settings app config coordination and memo snapshot settings.
 * - Scenarios:
 *   - Given emitted memo snapshot preference values, coordinator state flows expose them correctly.
 *   - Given disabling memo snapshots, coordinator turns off snapshotting.
 * - Observable outcomes:
 *   - Coordinator StateFlow values (memoSnapshotsEnabled, memoSnapshotMaxCount, memoSnapshotMaxAgeDays).
 *   - Backing repository state (snapshotsEnabled).
 * - TDD proof: Ensures coordinator exposes state flow and forwards the toggle accurately.
 * - Excludes: DataStore persistence internals, Compose rendering, day-file snapshot UI.
 */
class SettingsAppConfigCoordinatorSnapshotTest : AppFunSpec() {
    private val appConfigRepository = FakeAppConfigRepository()
    private val workspaceStateResolver = FakeWorkspaceStateResolver()
    private val switchRootStorageUseCase = SwitchRootStorageUseCase(appConfigRepository, workspaceStateResolver, FakeWorkspaceMutationLease(), com.lomo.app.testing.fakes.FakeEngineReadinessRepository())
    private val memoSnapshotPreferencesRepository = FakeMemoSnapshotPreferencesRepository()

    private class FakeWorkspaceStateResolver : WorkspaceStateResolver {
        override suspend fun rebuildFromCurrentWorkspace() {}
    }

    init {
        test("memo snapshot flows expose repository values") {
            runTest {
                memoSnapshotPreferencesRepository.snapshotsEnabled.value = false
                memoSnapshotPreferencesRepository.maxCount.value = 50
                memoSnapshotPreferencesRepository.maxAgeDays.value = 90

                val coordinator = SettingsAppConfigCoordinator(
                    appConfigRepository = appConfigRepository,
                    switchRootStorageUseCase = switchRootStorageUseCase,
                    scope = backgroundScope,
                    customFontStore = FakeCustomFontStore(),
                    memoSnapshotPreferencesRepository = memoSnapshotPreferencesRepository,
                    syncInboxRepository = FakeSyncInboxRepository(),
                )

                coordinator.memoSnapshotsEnabled.first { it == false } shouldBe false
                coordinator.memoSnapshotMaxCount.first { it == 50 } shouldBe 50
                coordinator.memoSnapshotMaxAgeDays.first { it == 90 } shouldBe 90
            }
        }

        test("disabling memo snapshots turns off recording") {
            runTest {
                memoSnapshotPreferencesRepository.snapshotsEnabled.value = true
                val coordinator = SettingsAppConfigCoordinator(
                    appConfigRepository = appConfigRepository,
                    switchRootStorageUseCase = switchRootStorageUseCase,
                    scope = backgroundScope,
                    customFontStore = FakeCustomFontStore(),
                    memoSnapshotPreferencesRepository = memoSnapshotPreferencesRepository,
                    syncInboxRepository = FakeSyncInboxRepository(),
                )

                coordinator.updateMemoSnapshotsEnabled(false)

                memoSnapshotPreferencesRepository.snapshotsEnabled.value shouldBe false
            }
        }
    }
}
