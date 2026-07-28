package com.lomo.domain.usecase

/*
 * Behavior Contract:
 * - Unit under test: SwitchRootStorageUseCase.
 * - Owning layer: domain.
 * - Priority tier: P0.
 * - Capability: prepare/validate candidate, take an exclusive mutation transition, persist selection, activate
 *   engine, rebuild, then reopen admissions. Failure before or during switch leaves the previous
 *   selection, previous engine, and previous index authoritative and always reopens admissions.
 *   Soft non-Ready activate
 *   is a failure. Rebuild failure after Room clear restores previous index via mandatory rebuild.
 *
 * Scenarios:
 * - Given a valid candidate, when switch succeeds, then the transition opens, selection persists,
 *   engine activates, rebuild runs, and admissions reopen.
 * - Given candidate validation fails, when switch is requested, then nothing is persisted and no
 *   transition
 *   never begins.
 * - Given persist fails inside the transition, when switch aborts, then admissions reopen and rebuild
 *   does not run.
 * - Given activate fails after persist, when switch aborts, then previous selection is restored,
 *   previous index is rebuilt, and admissions reopen.
 * - Given activate succeeds but rebuild fails after clear, when switch aborts, then previous
 *   selection+engine are restored and previous index is rebuilt (not left empty).
 *
 * Observable outcomes: ordered event log, transition count, admissibility, applied updates, activate calls,
 * rebuild counts.
 * TDD proof: fails before transition/validate/activate ordering is required by SwitchRootStorageUseCase;
 * A-SW-002 RED: rebuild-fail-after-clear left rebuildCount=1 without restore rebuild.
 * Excludes: concrete SAF/engine open validation and UI navigation.
 *
 * Test Change Justification:
 * - Reason category: production memo persistence cutover from Room to lomo-store ports.
 * - Old behavior/assertion being replaced: switch paths that treated Room clear/rebuild as the
 *   sole index authority after root change.
 * - Why old assertion is no longer correct: rebuild now targets the Rust store-backed index; Room
 *   clear is no longer the production recovery path.
 * - Coverage preserved by: transition/validate/persist/activate/rebuild ordering and restore-on-failure
 *   scenarios remain asserted.
 * - Why this is not fitting the test to the implementation: outcomes stay use-case event order and
 *   authority restore, not store SQL.
 */

import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.WorkspaceCandidateValidator
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeDirectorySettingsRepository
import com.lomo.domain.testing.fakes.FakeEngineReadinessRepository
import com.lomo.domain.testing.fakes.FakeWorkspaceStateResolver
import com.lomo.domain.testing.fakes.FakeWorkspaceMutationLease
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class SwitchRootStorageUseCaseTest : DomainFunSpec() {
    private val eventLog = mutableListOf<String>()
    private val directorySettingsRepository = FakeDirectorySettingsRepository(eventLog)
    private val workspaceStateResolver = FakeWorkspaceStateResolver(eventLog)
    private val workspaceMutationLease = FakeWorkspaceMutationLease()
    private val engineReadinessRepository = FakeEngineReadinessRepository()
    private lateinit var useCase: SwitchRootStorageUseCase

    init {
        beforeTest {
            eventLog.clear()
            directorySettingsRepository.applyFailure = null
            workspaceStateResolver.rebuildFailure = null
            workspaceStateResolver.remainingRebuildFailures = 0
            workspaceStateResolver.remainingRebuildFailure = null
                        engineReadinessRepository.remainingActivateFailures = 0
            engineReadinessRepository.activateCount = 0
            engineReadinessRepository.clearCount = 0
            engineReadinessRepository.activateFailure = IllegalStateException("open failed")
            useCase =
                SwitchRootStorageUseCase(
                    directorySettingsRepository = directorySettingsRepository,
                    workspaceStateResolver = workspaceStateResolver,
                    workspaceMutationLease = workspaceMutationLease,
                    engineReadinessRepository = engineReadinessRepository,
                    workspaceCandidateValidator =
                        WorkspaceCandidateValidator { location ->
                            eventLog += "workspace.validateCandidate"
                            require(location.raw.isNotBlank()) { "blank candidate" }
                        },
                )
        }

        test("updateRootLocation takes a transition, persists, activates engine, rebuilds, then reopens") {
            runTest {
                val previous = StorageLocation("/tmp/previous")
                directorySettingsRepository.setLocation(StorageArea.ROOT, previous)
                val location = StorageLocation("/tmp/lomo")

                useCase.updateRootLocation(location)

                directorySettingsRepository.appliedUpdates shouldBe
                    listOf(StorageAreaUpdate(StorageArea.ROOT, location))
                workspaceStateResolver.rebuildCallCount shouldBe 1
                engineReadinessRepository.activateCount shouldBe 1
                engineReadinessRepository.lastActivated shouldBe location
                workspaceMutationLease.transitionCount shouldBe 1
                workspaceMutationLease.isWritable() shouldBe true
                eventLog shouldBe
                    listOf(
                        "workspace.validateCandidate",
                        "directory.applyLocation:ROOT",
                        "workspace.rebuildFromCurrentWorkspace",
                    )
            }
        }

        test("updateRootLocation takes no transition and persists nothing when candidate validation fails") {
            runTest {
                val failing =
                    SwitchRootStorageUseCase(
                        directorySettingsRepository = directorySettingsRepository,
                        workspaceStateResolver = workspaceStateResolver,
                        workspaceMutationLease = workspaceMutationLease,
                        engineReadinessRepository = engineReadinessRepository,
                        workspaceCandidateValidator =
                            WorkspaceCandidateValidator {
                                eventLog += "workspace.validateCandidate"
                                error("candidate unavailable")
                            },
                    )

                val error = runCatching { failing.updateRootLocation(StorageLocation("/tmp/bad")) }.exceptionOrNull()

                error.shouldBeInstanceOf<IllegalStateException>()
                directorySettingsRepository.appliedUpdates shouldBe emptyList()
                workspaceStateResolver.rebuildCallCount shouldBe 0
                engineReadinessRepository.activateCount shouldBe 0
                workspaceMutationLease.transitionCount shouldBe 0
                eventLog shouldBe listOf("workspace.validateCandidate")
            }
        }

        test("updateRootLocation reopens admissions when persist fails") {
            runTest {
                directorySettingsRepository.applyFailure = IllegalStateException("failed")

                val error =
                    runCatching { useCase.updateRootLocation(StorageLocation("content://root")) }
                        .exceptionOrNull()

                error.shouldBeInstanceOf<IllegalStateException>()
                directorySettingsRepository.appliedUpdates shouldBe emptyList()
                workspaceStateResolver.rebuildCallCount shouldBe 0
                engineReadinessRepository.activateCount shouldBe 0
                workspaceMutationLease.transitionCount shouldBe 1
                workspaceMutationLease.isWritable() shouldBe true
            }
        }

        test("updateRootLocation restores previous selection when activate fails") {
            runTest {
                val previous = StorageLocation("/tmp/previous")
                directorySettingsRepository.setLocation(StorageArea.ROOT, previous)
                engineReadinessRepository.remainingActivateFailures = 1
                engineReadinessRepository.activateFailure = IllegalStateException("open failed")

                val error =
                    runCatching { useCase.updateRootLocation(StorageLocation("/tmp/candidate")) }
                        .exceptionOrNull()

                error.shouldBeInstanceOf<IllegalStateException>()
                // First apply candidate, then restore previous after activate failure.
                directorySettingsRepository.appliedUpdates shouldBe
                    listOf(
                        StorageAreaUpdate(StorageArea.ROOT, StorageLocation("/tmp/candidate")),
                        StorageAreaUpdate(StorageArea.ROOT, previous),
                    )
                // Activate failed before candidate rebuild; restore still re-scans previous index.
                workspaceStateResolver.rebuildCallCount shouldBe 1
                // activate attempted for candidate then for restore
                engineReadinessRepository.activateCount shouldBe 2
                workspaceMutationLease.transitionCount shouldBe 1
                workspaceMutationLease.isWritable() shouldBe true
            }
        }

        test("updateRootLocation surfaces restore failure when previous engine cannot reopen") {
            runTest {
                val previous = StorageLocation("/tmp/previous")
                directorySettingsRepository.setLocation(StorageArea.ROOT, previous)
                // Candidate activate fails, then restore activate also fails.
                engineReadinessRepository.remainingActivateFailures = 2
                engineReadinessRepository.activateFailure = IllegalStateException("open failed")

                val error =
                    runCatching { useCase.updateRootLocation(StorageLocation("/tmp/candidate")) }
                        .exceptionOrNull()

                val restoreError = error.shouldBeInstanceOf<WorkspaceAuthorityRestoreException>()
                restoreError.suppressed.single().shouldBeInstanceOf<IllegalStateException>()
                workspaceMutationLease.transitionCount shouldBe 1
                workspaceMutationLease.isWritable() shouldBe true
            }
        }

        test("updateRootLocation rebuilds previous index when rebuild fails after clear") {
            runTest {
                val previous = StorageLocation("/tmp/previous")
                directorySettingsRepository.setLocation(StorageArea.ROOT, previous)
                workspaceStateResolver.remainingRebuildFailures = 1
                workspaceStateResolver.remainingRebuildFailure = IllegalStateException("refresh failed")

                val error =
                    runCatching { useCase.updateRootLocation(StorageLocation("/tmp/candidate")) }
                        .exceptionOrNull()

                val rebuildError = error.shouldBeInstanceOf<IllegalStateException>()
                rebuildError.message shouldBe "refresh failed"
                directorySettingsRepository.appliedUpdates shouldBe
                    listOf(
                        StorageAreaUpdate(StorageArea.ROOT, StorageLocation("/tmp/candidate")),
                        StorageAreaUpdate(StorageArea.ROOT, previous),
                    )
                // Candidate rebuild failed once; restore rebuild must repopulate previous index.
                workspaceStateResolver.rebuildCallCount shouldBe 1
                engineReadinessRepository.activateCount shouldBe 2
                engineReadinessRepository.lastActivated shouldBe previous
                workspaceMutationLease.transitionCount shouldBe 1
                workspaceMutationLease.isWritable() shouldBe true
                eventLog shouldBe
                    listOf(
                        "workspace.validateCandidate",
                        "directory.applyLocation:ROOT",
                        "directory.applyLocation:ROOT",
                        "workspace.rebuildFromCurrentWorkspace",
                    )
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
