package com.lomo.app.feature.settings
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.app.testing.fakes.FakeCustomFontStore
import com.lomo.app.testing.fakes.FakeMemoSnapshotPreferencesRepository
import com.lomo.app.testing.fakes.FakeSyncInboxRepository
import com.lomo.app.testing.fakes.FakeWorkspaceMutationLease
import com.lomo.domain.repository.WorkspaceStateResolver
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Capability: Settings app config coordination and memo snapshot settings.
 * Scenarios:
 * - Given repository snapshot preferences, when coordinator flows are observed, then enabled state, maximum
 *   count, and maximum age match the repository.
 * - Given snapshot recording is disabled, when the toggle is applied, then the backing repository disables
 *   snapshot recording.
 *
 * - Observable outcomes:
 *   - Coordinator StateFlow values (memoSnapshotsEnabled, memoSnapshotMaxCount, memoSnapshotMaxAgeDays).
 *   - Backing repository state (snapshotsEnabled).
 * - TDD proof: Ensures coordinator exposes state flow and forwards the toggle accurately.
 * - Excludes: DataStore persistence internals, Compose rendering, day-file snapshot UI.
 *
 * Test Change Justification:
 * Reason category: Mechanical formatting and import alignment for SettingsAppConfigCoordinatorSnapshot.
 * Old behavior/assertion being replaced: None; existing scenario bodies and expected results are retained.
 * Why old assertion is no longer correct: Existing assertions remain correct. Layout, imports, and equivalent
 * fixture declaration syntax do not change their behavior.
 * Coverage preserved by: The existing scenarios, including memo snapshot flows expose repository values;
 * disabling memo snapshots turns off recording.
 * Why this is not fitting the test to the implementation: No expected result is relaxed or replaced; the
 * original behavior locks remain executable.
 */
class SettingsAppConfigCoordinatorSnapshotTest : AppFunSpec() {
    private val appConfigRepository = FakeAppConfigRepository()
    private val workspaceStateResolver = FakeWorkspaceStateResolver()
    private val switchRootStorageUseCase = SwitchRootStorageUseCase(
        appConfigRepository,
        workspaceStateResolver,
        FakeWorkspaceMutationLease(),
        com.lomo.app.testing.fakes.FakeEngineReadinessRepository(),
    )
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
