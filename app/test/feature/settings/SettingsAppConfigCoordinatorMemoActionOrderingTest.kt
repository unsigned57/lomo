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
 * - Capability: Settings app config coordination and memo action auto-reorder preference updates.
 * - Scenarios:
 *   - Given emitted auto-reorder preference value, coordinator state flow follows repository value.
 *   - Given preference toggle updates, coordinator writes value through to repository.
 * - Observable outcomes:
 *   - Coordinator memoActionAutoReorderEnabled StateFlow values.
 *   - Backing repository preference states.
 * - TDD proof: Ensures coordinator exposes state flow and forwards preference changes directly.
 * - Excludes: DataStore persistence internals, UI rendering.
 */
class SettingsAppConfigCoordinatorMemoActionOrderingTest : AppFunSpec() {
    private val appConfigRepository = FakeAppConfigRepository()
    private val workspaceStateResolver = FakeWorkspaceStateResolver()
    private val switchRootStorageUseCase = SwitchRootStorageUseCase(appConfigRepository, workspaceStateResolver)

    private class FakeWorkspaceStateResolver : WorkspaceStateResolver {
        override suspend fun rebuildFromCurrentWorkspace() {}
    }

    init {
        test("memo action auto reorder state flow follows repository value") {
            runTest {
                appConfigRepository.setMemoActionAutoReorderEnabled(false)

                val coordinator = SettingsAppConfigCoordinator(appConfigRepository, switchRootStorageUseCase, backgroundScope, FakeCustomFontStore())

                coordinator.memoActionAutoReorderEnabled.first { !it } shouldBe false
            }
        }

        test("updateMemoActionAutoReorderEnabled forwards value to repository") {
            runTest {
                val coordinator = SettingsAppConfigCoordinator(appConfigRepository, switchRootStorageUseCase, backgroundScope, FakeCustomFontStore())

                coordinator.updateMemoActionAutoReorderEnabled(false)

                appConfigRepository.isMemoActionAutoReorderEnabled().first() shouldBe false
            }
        }
    }
}
