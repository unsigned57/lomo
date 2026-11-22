package com.lomo.domain.usecase

import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.testing.fakes.FakeDirectorySettingsRepository
import com.lomo.domain.testing.fakes.FakeEngineReadinessRepository
import com.lomo.domain.testing.fakes.FakeWorkspaceMutationLease
import com.lomo.domain.testing.fakes.FakeWorkspaceStateResolver
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
 * Capability: selected media directories rebind under an exclusive workspace transition; owner: domain; P0.
 * Scenarios:
 * - Given a mounted workspace, when an image directory changes, then the engine reopens with the new selection.
 * - Given reactivation fails, when the previous directory was unconfigured, then configuration and engine are restored.
 * Observable outcomes: selected location, activation calls and transition count.
 * TDD proof: target/lomo/reports/manual/four-bugs/media-transition-red.log detects absent activation in the former settings-only path;
 * GREEN: same spec passes with the exclusive rebind.
 * Excludes: provider validation and Rust mounting internals.
 * Test Change Justification:
 * Reason category: domain contract changed.
 * Old behavior/assertion being replaced: failed activation leaves the old engine alive; two open attempts.
 * Why old assertion is no longer correct: native exclusive ownership requires retiring the old engine before a same-root rebind.
 * Coverage preserved by: failed and cancelled rebinds still restore the original selection, Ready engine and write admission.
 * Why this is not fitting the test to the implementation: MediaDirectoryOwnershipTest rejects concurrent same-root owners,
 * matching the workspace_busy rejection captured from real JNI on the device.
 */
class MediaDirectoryTransitionTest :
    FunSpec({
        test("given independent images when following notes then only the image override is removed") {
            runTest {
                val directories = FakeDirectorySettingsRepository()
                val workspace = StorageLocation("/workspace")
                val images = StorageLocation("/pictures")
                val recordings = StorageLocation("/recordings")
                directories.setLocation(StorageArea.ROOT, workspace)
                directories.setLocation(StorageArea.IMAGE, images)
                directories.setLocation(StorageArea.VOICE, recordings)
                val engine = FakeEngineReadinessRepository()
                engine.activateWorkspace(workspace, mediaChange = null)
                val useCase = SwitchRootStorageUseCase(
                    directories,
                    FakeWorkspaceStateResolver(),
                    FakeWorkspaceMutationLease(engine),
                    engine,
                )
                useCase.updateMediaLocation(StorageAreaUpdate(StorageArea.IMAGE, null))
                directories.currentLocation(StorageArea.IMAGE) shouldBe null
                directories.currentLocation(StorageArea.VOICE) shouldBe recordings
                directories.pendingRootTransition() shouldBe null
                engine.activeWorkspaceLocation.value shouldBe workspace
            }
        }
        test(
            "given a mounted workspace when selecting images then the same workspace reopens after configuration changes",
        ) {
            runTest {
                val directories = FakeDirectorySettingsRepository()
                val engine = FakeEngineReadinessRepository()
                val workspace = StorageLocation("/workspace")
                engine.activateWorkspace(workspace, mediaChange = null)
                val lease = FakeWorkspaceMutationLease(engine)
                val useCase = SwitchRootStorageUseCase(directories, FakeWorkspaceStateResolver(), lease, engine)
                useCase.updateMediaLocation(StorageAreaUpdate(StorageArea.IMAGE, StorageLocation("/pictures")))
                directories.currentLocation(StorageArea.IMAGE) shouldBe StorageLocation("/pictures")
                engine.activateCount shouldBe 2
                engine.lastActivated shouldBe workspace
                lease.transitionCount shouldBe 1
            }
        }
        test(
            "given reactivation fails when selecting a first media directory then the unconfigured state and workspace are restored",
        ) {
            runTest {
                val directories = FakeDirectorySettingsRepository()
                val engine = FakeEngineReadinessRepository()
                val workspace = StorageLocation("/workspace")
                engine.activateWorkspace(workspace, mediaChange = null)
                engine.remainingActivateFailures = 1
                val lease = FakeWorkspaceMutationLease(engine)
                val useCase = SwitchRootStorageUseCase(directories, FakeWorkspaceStateResolver(), lease, engine)
                val result = runCatching {
                    useCase.updateMediaLocation(StorageAreaUpdate(StorageArea.VOICE, StorageLocation("/recordings")))
                }
                result.exceptionOrNull()?.message shouldBe "activate failed"
                directories.currentLocation(StorageArea.VOICE) shouldBe null
                engine.activeWorkspaceLocation.value shouldBe workspace
                engine.activateCount shouldBe 3
                lease.isWritable() shouldBe true
            }
        }

        test("given cancellation during media rebind then the original configuration and active engine remain usable") {
            runTest {
                val directories = FakeDirectorySettingsRepository()
                val engine = FakeEngineReadinessRepository()
                val workspace = StorageLocation("/workspace")
                engine.activateWorkspace(workspace, mediaChange = null)
                engine.remainingActivateFailures = 1
                engine.activateFailure = kotlinx.coroutines.CancellationException("cancelled rebind")
                val lease = FakeWorkspaceMutationLease(engine)
                val useCase = SwitchRootStorageUseCase(directories, FakeWorkspaceStateResolver(), lease, engine)
                val result = runCatching {
                    useCase.updateMediaLocation(StorageAreaUpdate(StorageArea.IMAGE, StorageLocation("/pictures")))
                }
                result.exceptionOrNull()?.javaClass shouldBe kotlinx.coroutines.CancellationException::class.java
                directories.currentLocation(StorageArea.IMAGE) shouldBe null
                engine.activeWorkspaceLocation.value shouldBe workspace
                lease.isWritable() shouldBe true
            }
        }
        test("given no mounted workspace when selecting media then only configuration changes") {
            runTest {
                val directories = FakeDirectorySettingsRepository()
                val engine = FakeEngineReadinessRepository()
                engine.clearWorkspace()
                val lease = FakeWorkspaceMutationLease(engine)
                val useCase = SwitchRootStorageUseCase(directories, FakeWorkspaceStateResolver(), lease, engine)
                useCase.updateMediaLocation(StorageAreaUpdate(StorageArea.VOICE, StorageLocation("/recordings")))
                directories.currentLocation(StorageArea.VOICE) shouldBe StorageLocation("/recordings")
                engine.activateCount shouldBe 0
                engine.activeWorkspaceLocation.value shouldBe null
            }
        }
    })
