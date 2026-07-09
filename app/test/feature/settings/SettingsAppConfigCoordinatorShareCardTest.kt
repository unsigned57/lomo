package com.lomo.app.feature.settings

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
import com.lomo.domain.repository.WorkspaceStateResolver
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Capability: Settings app config coordination and share-card settings signature text updates.
 * - Scenarios:
 *   - Given initial signature text, coordinator exposes signature text value.
 *   - Given signature text updates, coordinator writes value through to repository.
 * - Observable outcomes:
 *   - Coordinator shareCardSignatureText StateFlow values.
 *   - Backing repository signature text.
 * - TDD proof: Ensures coordinator exposes state flow and forwards signature text updates directly.
 * - Excludes: DataStore persistence internals, UI rendering, and bitmap rendering.
 */
class SettingsAppConfigCoordinatorShareCardTest : AppFunSpec() {
    private val appConfigRepository = FakeAppConfigRepository()
    private val workspaceStateResolver = FakeWorkspaceStateResolver()
    private val switchRootStorageUseCase = SwitchRootStorageUseCase(appConfigRepository, workspaceStateResolver)

    private class FakeWorkspaceStateResolver : WorkspaceStateResolver {
        override suspend fun rebuildFromCurrentWorkspace() {}
    }

    init {
        test("share card settings expose signature text") {
            runTest {
                appConfigRepository.setShareCardSignatureText("Unsigned57")

                val coordinator = SettingsAppConfigCoordinator(appConfigRepository, switchRootStorageUseCase, backgroundScope, FakeCustomFontStore())

                coordinator.shareCardSignatureText.first { it == "Unsigned57" } shouldBe "Unsigned57"
            }
        }

        test("share card settings forward signature text updates") {
            runTest {
                val coordinator = SettingsAppConfigCoordinator(appConfigRepository, switchRootStorageUseCase, backgroundScope, FakeCustomFontStore())

                coordinator.updateShareCardSignatureText("Unsigned57")

                appConfigRepository.getShareCardSignatureText().first() shouldBe "Unsigned57"
            }
        }
    }
}
