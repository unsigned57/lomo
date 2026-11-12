package com.lomo.domain.usecase

import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.WorkspaceCandidateValidator
import com.lomo.domain.testing.fakes.FakeDirectorySettingsRepository
import com.lomo.domain.testing.fakes.FakeEngineReadinessRepository
import com.lomo.domain.testing.fakes.FakeWorkspaceMutationLease
import com.lomo.domain.testing.fakes.FakeWorkspaceStateResolver
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * Scenarios also cover cold recovery before new prepare, serial notes/voice requests and
 * uncommitted attachment configuration during candidate indexing.
 * TDD proof: target/lomo/reports/manual/directory-selection/startup-order-red.log observes a
 * live PREPARED journal before startup recovery settled; verification-green.log verifies ordering.
 * Test Change Justification:
 * Reason category: explicit activation input adaptation.
 * Old behavior/assertion being replaced: root-only activations implicitly read attachment candidates from a journal.
 * Why old assertion is no longer correct: activation now receives a prepared attachment change explicitly;
 * these existing root-only calls select the committed attachment configuration with a null change.
 * Coverage preserved by: every existing mount, state and failure assertion is retained without weakening.
 * Why this is not fitting the test to the implementation: stale pending candidates must not influence restoration.
 * TDD proof: Regression preservation - transaction-green-first.log and verification-green.log under
 * target/lomo/reports/manual/directory-selection/; index-validation-red.log exposes premature activation.
 * Capability: an admitted directory selection finishes independently of its screen; owner: domain; priority: P0.
 * Scenarios:
 * - Given any of the three storage areas, when its caller is cancelled during candidate validation,
 *   then a valid candidate still commits and the mounted workspace remains usable.
 * Observable outcomes: committed selection and engine readiness after the caller has joined.
 * TDD proof: directory-selection/domain-red.log, transaction-red.log and verification-green.log under target/lomo/reports/manual/.
 * Excludes: Android picker launch, provider I/O and process death.
 */
class DirectorySelectionLifetimeTest :
    FunSpec({
        test(
            "given cold recovery is unfinished when a directory is selected then no new journal is prepared until recovery settles",
        ) {
            runTest {
                val directories = FakeDirectorySettingsRepository()
                val engine = FakeEngineReadinessRepository()
                val old = StorageLocation("/old-notes")
                val candidate = StorageLocation("/next-notes")
                directories.setLocation(StorageArea.ROOT, old)
                engine.activateWorkspace(old, mediaChange = null)
                val startup = CompletableDeferred<Unit>()
                engine.activateCheckpoint = { startup.await() }
                val readiness = object : com.lomo.domain.repository.EngineReadinessRepository by engine {
                    override suspend fun requestEngineStart(): com.lomo.domain.model.EngineReadiness {
                        startup.await()
                        directories.recoverRootLocation()
                        return engine.readiness.value
                    }
                }
                val useCase = SwitchRootStorageUseCase(
                    directories,
                    FakeWorkspaceStateResolver(),
                    FakeWorkspaceMutationLease(readiness),
                    readiness,
                )
                val caller = launch(start = CoroutineStart.UNDISPATCHED) { useCase.updateRootLocation(candidate) }
                val journalBeforeStartup = directories.pendingRootTransition()
                startup.complete(Unit)
                caller.join()

                journalBeforeStartup shouldBe null
                directories.currentRootLocation() shouldBe candidate
                directories.pendingRootTransition() shouldBe null
            }
        }
        test("given a notes switch in progress when voice selection follows then both transactions finish in order") {
            runTest {
                val directories = FakeDirectorySettingsRepository()
                val engine = FakeEngineReadinessRepository()
                val old = StorageLocation("/old-notes")
                val next = StorageLocation("/next-notes")
                val voice = StorageLocation("/selected-voice")
                directories.setLocation(StorageArea.ROOT, old)
                engine.activateWorkspace(old, mediaChange = null)
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                engine.activateCheckpoint = {
                    if (!entered.isCompleted) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
                val lease = FakeWorkspaceMutationLease(engine)
                val useCase = SwitchRootStorageUseCase(directories, FakeWorkspaceStateResolver(), lease, engine)
                val notesCaller = launch(start = CoroutineStart.UNDISPATCHED) { useCase.updateRootLocation(next) }
                entered.await()
                val voiceCaller = launch(start = CoroutineStart.UNDISPATCHED) {
                    useCase.updateMediaLocation(StorageAreaUpdate(StorageArea.VOICE, voice))
                }
                notesCaller.cancel()
                release.complete(Unit)
                notesCaller.join()
                voiceCaller.join()

                directories.currentRootLocation() shouldBe next
                directories.currentLocation(StorageArea.VOICE) shouldBe voice
                directories.pendingRootTransition() shouldBe null
                engine.activeWorkspaceLocation.value shouldBe next
                lease.transitionCount shouldBe 2
            }
        }
        for (area in listOf(StorageArea.IMAGE, StorageArea.VOICE)) {
            test(
                "given $area rebinding when candidate indexing runs then selection remains uncommitted and recovery is journaled",
            ) {
                runTest {
                    val directories = FakeDirectorySettingsRepository()
                    val engine = FakeEngineReadinessRepository()
                    val workspace = StorageLocation("/notes")
                    val previous = StorageLocation("/previous-${area.name}")
                    val candidate = StorageLocation("/candidate-${area.name}")
                    directories.setLocation(StorageArea.ROOT, workspace)
                    directories.setLocation(area, previous)
                    engine.activateWorkspace(workspace, mediaChange = null)
                    engine.activateCheckpoint = {
                        directories.currentLocation(area) shouldBe previous
                        directories.pendingRootTransition() shouldNotBe null
                    }
                    SwitchRootStorageUseCase(
                        directories,
                        FakeWorkspaceStateResolver(),
                        FakeWorkspaceMutationLease(engine),
                        engine,
                    ).updateMediaLocation(StorageAreaUpdate(area, candidate))

                    directories.currentLocation(area) shouldBe candidate
                    directories.pendingRootTransition() shouldBe null
                }
            }
        }
        for (area in listOf(StorageArea.ROOT, StorageArea.IMAGE, StorageArea.VOICE)) {
            test("given $area when the caller closes during validation then selection still commits") {
                runTest {
                    val directories = FakeDirectorySettingsRepository()
                    val engine = FakeEngineReadinessRepository()
                    val previous = StorageLocation("/notes")
                    directories.setLocation(StorageArea.ROOT, previous)
                    engine.activateWorkspace(previous, mediaChange = null)
                    val validating = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val useCase = SwitchRootStorageUseCase(
                        directories,
                        FakeWorkspaceStateResolver(),
                        FakeWorkspaceMutationLease(engine),
                        engine,
                        WorkspaceCandidateValidator {
                            validating.complete(Unit)
                            release.await()
                        },
                    )
                    val candidate = StorageLocation("/selected-${area.name}")
                    val caller = launch(start = CoroutineStart.UNDISPATCHED) {
                        if (area == StorageArea.ROOT) {
                            useCase.updateRootLocation(candidate)
                        } else {
                            useCase.updateMediaLocation(StorageAreaUpdate(area, candidate))
                        }
                    }
                    validating.await()
                    caller.cancel()
                    release.complete(Unit)
                    caller.join()

                    directories.currentLocation(area) shouldBe candidate
                    engine.activeWorkspaceLocation.value shouldBe if (area == StorageArea.ROOT) candidate else previous
                    engine.readiness.value shouldBe com.lomo.domain.model.EngineReadiness.Ready
                }
            }
        }
    })
