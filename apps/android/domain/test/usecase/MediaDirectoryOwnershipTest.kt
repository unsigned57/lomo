package com.lomo.domain.usecase

import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.testing.fakes.FakeDirectorySettingsRepository
import com.lomo.domain.testing.fakes.FakeEngineReadinessRepository
import com.lomo.domain.testing.fakes.FakeWorkspaceMutationLease
import com.lomo.domain.testing.fakes.FakeWorkspaceStateResolver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * Test Change Justification:
 * Reason category: explicit activation input adaptation.
 * Old behavior/assertion being replaced: root-only activations implicitly read attachment candidates from a journal.
 * Why old assertion is no longer correct: activation now receives a prepared attachment change explicitly;
 * these existing root-only calls select the committed attachment configuration with a null change.
 * Coverage preserved by: every existing mount, state and failure assertion is retained without weakening.
 * Why this is not fitting the test to the implementation: stale pending candidates must not influence restoration.
 * TDD proof: Regression preservation - transaction-green-first.log and verification-green.log under
 * target/lomo/reports/manual/directory-selection/; index-validation-red.log exposes premature activation.
 * Capability: media selection respects exclusive native workspace ownership; owner: domain; priority: P0.
 * Scenarios:
 * - Given a mounted workspace with a sole-owner open constraint, when images or voice change,
 *   then the old engine retires before reactivation and the new directory remains selected.
 * - Given the new mount fails, when selection rolls back, then the previous directory and a Ready
 *   workspace are restored before writes resume, and the original failure is returned.
 * - Given restoration also fails, then a structured authority failure retains the original error and writes stay blocked.
 * Observable outcomes: directory selection, mounted location/readiness, write admission and failure message.
 * TDD proof: target/lomo/reports/manual/media-directory-update/ownership-red.log;
 * RED: workspace_busy is thrown by the same-location ownership guard; GREEN: the same spec passes.
 * Excludes: SAF grant implementation and Rust's internal lock mechanics; device evidence covers real JNI.
 */
class MediaDirectoryOwnershipTest :
    FunSpec({
        test("given sole workspace ownership when either media directory changes then new selection mounts Ready") {
            runTest {
                for (area in listOf(StorageArea.IMAGE, StorageArea.VOICE)) {
                    val directories = FakeDirectorySettingsRepository()
                    val engine = FakeEngineReadinessRepository()
                    val workspace = StorageLocation("/workspace")
                    engine.activateWorkspace(workspace, mediaChange = null)
                    engine.activateCheckpoint = {
                        check(engine.activeWorkspaceLocation.value == null) { "workspace_busy" }
                    }
                    val lease = FakeWorkspaceMutationLease(engine)
                    val useCase = SwitchRootStorageUseCase(directories, FakeWorkspaceStateResolver(), lease, engine)
                    val selected = StorageLocation("/selected-${area.name}")

                    useCase.updateMediaLocation(StorageAreaUpdate(area, selected))

                    directories.currentLocation(area) shouldBe selected
                    engine.activeWorkspaceLocation.value shouldBe workspace
                    engine.readiness.value shouldBe EngineReadiness.Ready
                    lease.isWritable() shouldBe true
                }
            }
        }

        test(
            "given a rejected new media mount when selection rolls back then the prior engine reopens before writes resume",
        ) {
            runTest {
                val directories = FakeDirectorySettingsRepository()
                val previous = StorageLocation("/old-images")
                directories.applyLocation(StorageAreaUpdate(StorageArea.IMAGE, previous))
                val engine = FakeEngineReadinessRepository()
                val workspace = StorageLocation("/workspace")
                engine.activateWorkspace(workspace, mediaChange = null)
                val lease = FakeWorkspaceMutationLease(engine)
                val rejected = IllegalStateException("selected directory unavailable")
                engine.remainingActivateFailures = 1
                engine.activateFailure = rejected
                engine.activateCheckpoint = {
                    check(engine.activeWorkspaceLocation.value == null) { "workspace_busy" }
                    lease.isWritable() shouldBe false
                }
                val useCase = SwitchRootStorageUseCase(directories, FakeWorkspaceStateResolver(), lease, engine)

                val error = shouldThrow<IllegalStateException> {
                    useCase.updateMediaLocation(StorageAreaUpdate(StorageArea.IMAGE, StorageLocation("/new-images")))
                }

                error.message shouldBe rejected.message
                directories.currentLocation(StorageArea.IMAGE) shouldBe previous
                engine.activeWorkspaceLocation.value shouldBe workspace
                engine.readiness.value shouldBe EngineReadiness.Ready
                lease.isWritable() shouldBe true
            }
        }

        test(
            "given both new mount and restoration fail then the authority error retains the original failure and writes stay blocked",
        ) {
            runTest {
                val directories = FakeDirectorySettingsRepository()
                val previous = StorageLocation("/old-voice")
                directories.applyLocation(StorageAreaUpdate(StorageArea.VOICE, previous))
                val engine = FakeEngineReadinessRepository()
                val workspace = StorageLocation("/workspace")
                engine.activateWorkspace(workspace, mediaChange = null)
                engine.remainingActivateFailures = 2
                engine.activateCheckpoint = { kotlinx.coroutines.yield() }
                val lease = FakeWorkspaceMutationLease(engine)
                val useCase = SwitchRootStorageUseCase(directories, FakeWorkspaceStateResolver(), lease, engine)

                val error = shouldThrow<WorkspaceAuthorityRestoreException> {
                    useCase.updateMediaLocation(StorageAreaUpdate(StorageArea.VOICE, StorageLocation("/new-voice")))
                }

                directories.currentLocation(StorageArea.VOICE) shouldBe previous
                engine.activeWorkspaceLocation.value shouldBe null
                lease.isWritable() shouldBe false
                error.cause.shouldBeInstanceOf<IllegalStateException>().message shouldBe "activate failed"
                error.cause shouldBe engine.activateFailure
                error.suppressed.single().message shouldBe "activate failed"
            }
        }
    })
