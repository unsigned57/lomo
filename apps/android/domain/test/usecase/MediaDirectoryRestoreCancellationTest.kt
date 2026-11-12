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
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
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
 * Capability: failed media rebind restoration preserves cancellation and restores directory selection; owner: domain; P0.
 * Scenarios:
 * - Given candidate activation fails and restoring authority is cancelled, when rebinding, then cancellation propagates with the previous directory selected.
 * - Given candidate activation is cancelled, when the transition exits, then prior selection and engine authority are restored before cancellation escapes.
 * Observable outcomes: cancellation type/message, prior selection and no fabricated active workspace.
 * TDD proof: target/lomo/reports/manual/directory-layout/cancellation-red.log wraps cancellation as an authority error;
 * GREEN: cancellation-green.log in the same evidence directory.
 * Candidate cancellation RED/GREEN: manual/memo-latency-capsules/media-cancel-{red,green}.log.
 * Excludes: OS provider execution and native engine destruction.
 */
class MediaDirectoryRestoreCancellationTest :
    FunSpec({
        test("given cancelled candidate activation when a media rebind exits then previous authority is restored") {
            runTest {
                val directories = FakeDirectorySettingsRepository()
                val previous = StorageLocation("/old-images")
                directories.setLocation(StorageArea.IMAGE, previous)
                val engine = FakeEngineReadinessRepository()
                val workspace = StorageLocation("/notes")
                engine.activateWorkspace(workspace, mediaChange = null)
                var attempts = 0
                engine.activateCheckpoint = {
                    attempts += 1
                    if (attempts == 1) throw CancellationException("candidate cancelled")
                }
                val useCase = SwitchRootStorageUseCase(
                    directories,
                    FakeWorkspaceStateResolver(),
                    FakeWorkspaceMutationLease(engine),
                    engine,
                )
                val failure = runCatching {
                    useCase.updateMediaLocation(StorageAreaUpdate(StorageArea.IMAGE, StorageLocation("/new-images")))
                }.exceptionOrNull()
                failure.shouldBeInstanceOf<CancellationException>().message shouldBe "candidate cancelled"
                directories.currentLocation(StorageArea.IMAGE) shouldBe previous
                engine.activeWorkspaceLocation.value shouldBe workspace
            }
        }

        test("given cancelled authority restoration when a media rebind fails then cancellation remains observable") {
            runTest {
                val directories = FakeDirectorySettingsRepository()
                val previous = StorageLocation("/old-images")
                directories.setLocation(StorageArea.IMAGE, previous)
                val engine = FakeEngineReadinessRepository()
                engine.activateWorkspace(StorageLocation("/notes"), mediaChange = null)
                var attempts = 0
                engine.activateCheckpoint = {
                    attempts += 1
                    if (attempts == 1) throw IllegalStateException("candidate activation failed")
                    throw CancellationException("restore cancelled")
                }
                val useCase = SwitchRootStorageUseCase(
                    directories,
                    FakeWorkspaceStateResolver(),
                    FakeWorkspaceMutationLease(engine),
                    engine,
                )
                val failure = runCatching {
                    useCase.updateMediaLocation(StorageAreaUpdate(StorageArea.IMAGE, StorageLocation("/new-images")))
                }.exceptionOrNull()
                failure.shouldBeInstanceOf<CancellationException>().message shouldBe "restore cancelled"
                directories.currentLocation(StorageArea.IMAGE) shouldBe previous
                engine.activeWorkspaceLocation.value shouldBe null
            }
        }
    })
