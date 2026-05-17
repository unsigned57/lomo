package com.lomo.domain.usecase

import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.WorkspaceStateResolver
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.runs
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: SwitchRootStorageUseCase.
 * - Behavior focus: switching workspace roots must persist the new root and then rebuild
 *   workspace-derived state from the newly selected local workspace without routing through the
 *   ordinary sync refresh pipeline.
 * - Observable outcomes: ordered root persistence and local workspace rebuild calls plus failure
 *   short-circuit behavior.
 * - Red phase: Fails before the fix because updateRootLocation routes through RefreshMemosUseCase,
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
    @MockK(relaxed = true)
    private lateinit var directorySettingsRepository: DirectorySettingsRepository

    @MockK(relaxed = true)
    private lateinit var workspaceStateResolver: WorkspaceStateResolver

    private lateinit var useCase: SwitchRootStorageUseCase
    init {
        beforeTest {
            MockKAnnotations.init(this@SwitchRootStorageUseCaseTest)
            useCase = SwitchRootStorageUseCase(directorySettingsRepository, workspaceStateResolver)
        }
    }
    init {
        test("updateRootLocation rebuilds current workspace after successful switch") {
            runTest {
                        val location = StorageLocation("/tmp/lomo")
                        coEvery { directorySettingsRepository.applyRootLocation(location) } just runs
                        coEvery { workspaceStateResolver.rebuildFromCurrentWorkspace() } just runs

                        useCase.updateRootLocation(location)

                        coVerifyOrder {
                            directorySettingsRepository.applyRootLocation(location)
                            workspaceStateResolver.rebuildFromCurrentWorkspace()
                        }
                    }
        }
    }
    init {
        test("updateRootLocation does not cleanup when switch fails") {
            runTest {
                        val location = StorageLocation("content://root")
                        coEvery { directorySettingsRepository.applyRootLocation(location) } throws IllegalStateException("failed")

                        val error = runCatching { useCase.updateRootLocation(location) }.exceptionOrNull()

                        error.shouldBeInstanceOf<IllegalStateException>()
                        coVerify(exactly = 1) { directorySettingsRepository.applyRootLocation(location) }
                        coVerify(exactly = 0) { workspaceStateResolver.rebuildFromCurrentWorkspace() }
                    }
        }
    }
    init {
        test("rebuildCurrentWorkspace delegates to local workspace resolver") {
            runTest {
                        coEvery { workspaceStateResolver.rebuildFromCurrentWorkspace() } just runs

                        useCase.rebuildCurrentWorkspace()

                        coVerify(exactly = 1) { workspaceStateResolver.rebuildFromCurrentWorkspace() }
                    }
        }
    }
}
