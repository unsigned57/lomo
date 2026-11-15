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
 * - Capability: Settings app config coordination and share-card settings signature text updates.
 * Scenarios:
 * - Given repository signature text, when coordinator state is observed, then the same text is exposed.
 * - Given a signature update, when the coordinator applies it, then the backing repository stores that exact
 *   text.
 *
 * - Observable outcomes:
 *   - Coordinator shareCardSignatureText StateFlow values.
 *   - Backing repository signature text.
 * - TDD proof: Ensures coordinator exposes state flow and forwards signature text updates directly.
 * - Excludes: DataStore persistence internals, UI rendering, and bitmap rendering.
 *
 * Test Change Justification:
 * Reason category: Mechanical formatting and import alignment for SettingsAppConfigCoordinatorShareCard.
 * Old behavior/assertion being replaced: None; existing scenario bodies and expected results are retained.
 * Why old assertion is no longer correct: Existing assertions remain correct. Layout, imports, and equivalent
 * fixture declaration syntax do not change their behavior.
 * Coverage preserved by: The existing scenarios, including share card settings expose signature text; share
 * card settings forward signature text updates.
 * Why this is not fitting the test to the implementation: No expected result is relaxed or replaced; the
 * original behavior locks remain executable.
 */
class SettingsAppConfigCoordinatorShareCardTest : AppFunSpec() {
    private val appConfigRepository = FakeAppConfigRepository()
    private val workspaceStateResolver = FakeWorkspaceStateResolver()
    private val switchRootStorageUseCase = SwitchRootStorageUseCase(
        appConfigRepository,
        workspaceStateResolver,
        FakeWorkspaceMutationLease(),
        com.lomo.app.testing.fakes.FakeEngineReadinessRepository(),
    )

    private class FakeWorkspaceStateResolver : WorkspaceStateResolver {
        override suspend fun rebuildFromCurrentWorkspace() {}
    }

    init {
        test("share card settings expose signature text") {
            runTest {
                appConfigRepository.setShareCardSignatureText("Unsigned57")

                val coordinator = SettingsAppConfigCoordinator(
                    appConfigRepository,
                    switchRootStorageUseCase,
                    backgroundScope,
                    FakeCustomFontStore(),
                    memoSnapshotPreferencesRepository = FakeMemoSnapshotPreferencesRepository(),
                    syncInboxRepository = FakeSyncInboxRepository(),
                )

                coordinator.shareCardSignatureText.first { it == "Unsigned57" } shouldBe "Unsigned57"
            }
        }

        test("share card settings forward signature text updates") {
            runTest {
                val coordinator = SettingsAppConfigCoordinator(
                    appConfigRepository,
                    switchRootStorageUseCase,
                    backgroundScope,
                    FakeCustomFontStore(),
                    memoSnapshotPreferencesRepository = FakeMemoSnapshotPreferencesRepository(),
                    syncInboxRepository = FakeSyncInboxRepository(),
                )

                coordinator.updateShareCardSignatureText("Unsigned57")

                appConfigRepository.getShareCardSignatureText().first() shouldBe "Unsigned57"
            }
        }
    }
}
