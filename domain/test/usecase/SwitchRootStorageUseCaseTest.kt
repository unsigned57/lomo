package com.lomo.domain.usecase

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


import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeDirectorySettingsRepository
import com.lomo.domain.testing.fakes.FakeWorkspaceStateResolver
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: SwitchRootStorageUseCase.
 * - Behavior focus: switching workspace roots must persist the new root and then rebuild
 *   workspace-derived state from the newly selected local workspace without routing through the
 *   ordinary sync refresh pipeline.
 * - Observable outcomes: ordered root persistence and local workspace rebuild calls plus failure
 *   short-circuit behavior.
 * - TDD proof: Fails before the fix because updateRootLocation routes through RefreshMemosUseCase,
 *   allowing sync refresh behavior to run after a root switch and leave the selected workspace partially rebuilt.
 * - Excludes: concrete DataStore/Room cleanup, file-system scanning, and UI navigation.
 */
/*
 * Test Change Justification:
 * - Reason category: product contract corrected after a user-visible root-switch partial-list bug.
 * - Old behavior/assertion being replaced: the previous companion assertion required the ordinary
 *   RefreshMemosUseCase after cleanup.
 * - Why old assertion is no longer correct: ordinary refresh can include sync refresh behavior and is
 *   not the root-switch contract; the switch must rebuild the currently configured local workspace directly.
 * - Coverage preserved by: ordered root persistence and failure short-circuit assertions remain, and
 *   RefreshingWorkspaceStateResolver covers the data-side cleanup, markdown refresh, and media refresh order.
 * - Why this is not fitting the test to the implementation: the corrected assertion protects the reported
 *   product behavior that switching workspaces must load the selected directory's full local memo set.
 */
class SwitchRootStorageUseCaseTest : DomainFunSpec() {
    private val eventLog = mutableListOf<String>()
    private val directorySettingsRepository = FakeDirectorySettingsRepository(eventLog)
    private val workspaceStateResolver = FakeWorkspaceStateResolver(eventLog)
    private lateinit var useCase: SwitchRootStorageUseCase

    init {
        beforeTest {
            eventLog.clear()
            directorySettingsRepository.applyFailure = null
            workspaceStateResolver.rebuildFailure = null
            useCase = SwitchRootStorageUseCase(directorySettingsRepository, workspaceStateResolver)
        }

        test("updateRootLocation rebuilds current workspace after successful switch") {
            runTest {
                val location = StorageLocation("/tmp/lomo")

                useCase.updateRootLocation(location)

                directorySettingsRepository.appliedUpdates shouldBe
                    listOf(StorageAreaUpdate(StorageArea.ROOT, location))
                workspaceStateResolver.rebuildCallCount shouldBe 1
                eventLog shouldBe
                    listOf(
                        "directory.applyLocation:ROOT",
                        "workspace.rebuildFromCurrentWorkspace",
                    )
            }
        }

        test("updateRootLocation does not cleanup when switch fails") {
            runTest {
                val location = StorageLocation("content://root")
                directorySettingsRepository.applyFailure = IllegalStateException("failed")

                val error = runCatching { useCase.updateRootLocation(location) }.exceptionOrNull()

                error.shouldBeInstanceOf<IllegalStateException>()
                directorySettingsRepository.appliedUpdates shouldBe emptyList()
                workspaceStateResolver.rebuildCallCount shouldBe 0
                eventLog shouldBe emptyList()
            }
        }

        test("rebuildCurrentWorkspace delegates to local workspace resolver") {
            runTest {
                useCase.rebuildCurrentWorkspace()

                workspaceStateResolver.rebuildCallCount shouldBe 1
                eventLog shouldBe listOf("workspace.rebuildFromCurrentWorkspace")
            }
        }
    }
}
