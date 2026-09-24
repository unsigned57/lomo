package com.lomo.domain.usecase

/*
 * Behavior Contract:
 * - Unit under test: SwitchRootStorageUseCase.
 * - Owning layer: domain.
 * - Priority tier: P0.
 * - Capability: validate a candidate, durably prepare while committed selection remains unchanged,
 *   activate the engine-owned projection transaction, atomically commit, then reopen admissions.
 *   Failure restores previous engine authority, rolls back the journal, and reopens admissions.
 *   Soft non-Ready activation is a failure.
 *
 * Scenarios:
 * - Given a valid candidate, when switch succeeds, then the transition opens, selection persists,
 *   engine activation commits its projection, no second rebuild runs, and admissions reopen.
 * - Given candidate validation fails, when switch is requested, then nothing is persisted and no
 *   transition
 *   never begins.
 * - Given persist fails inside the transition, when switch aborts, then admissions reopen and rebuild
 *   does not run.
 * - Given activate fails after persist, when switch aborts, then previous selection and engine are
 *   restored through the same activation boundary, no external rebuild runs, and admissions reopen.
 * - Given Recovery on the committed location, when the same location is requested again, then a new
 *   activation generation runs without opening another root-transition journal.
 * - Given Ready on the committed location, when the same location is requested again, then activation
 *   is skipped.
 *
 * Observable outcomes: ordered event log, transition count, admissibility, applied updates, activate calls,
 * absence of duplicate rebuilds.
 * TDD proof: fails before transition/validate/activate ordering is required by SwitchRootStorageUseCase;
 * A-SW-003 RED: successful activation still invoked the obsolete second rebuild owner.
 * Excludes: concrete SAF/engine open validation and UI navigation.
 *
 * Test Change Justification:
 * - Reason category: production memo persistence cutover from Room to lomo-store ports.
 * - Old behavior/assertion being replaced: switch and rollback paths invoked WorkspaceStateResolver
 *   after ManagedEngineSession activation had already rebuilt the Rust projection.
 * - Why old assertion is no longer correct: activation owns open, rebuild, verification, and promote
 *   as one transaction; a second rebuild duplicates SAF full scans and can desynchronize authority.
 * - Coverage preserved by: transition/validate/persist/activate ordering, restore-on-failure, and the
 *   explicit manual rebuild delegation scenario remain asserted.
 * - Coverage also preserved by: same-location Recovery retry reactivates; same-location Ready is a no-op.
 * - Why this is not fitting the test to the implementation: outcomes stay use-case event order and
 *   authority restore, not store SQL.
 */

import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.EngineRetryDisposition
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

        test("updateRootLocation commits through activation without a second rebuild then reopens") {
            runTest {
                val previous = StorageLocation("/tmp/previous")
                directorySettingsRepository.setLocation(StorageArea.ROOT, previous)
                val location = StorageLocation("/tmp/lomo")

                useCase.updateRootLocation(location)

                directorySettingsRepository.appliedUpdates shouldBe emptyList()
                workspaceStateResolver.rebuildCallCount shouldBe 0
                engineReadinessRepository.activateCount shouldBe 1
                engineReadinessRepository.lastActivated shouldBe location
                workspaceMutationLease.transitionCount shouldBe 1
                workspaceMutationLease.isWritable() shouldBe true
                eventLog shouldBe
                    listOf(
                        "workspace.validateCandidate",
                        "directory.prepareRootTransition",
                        "directory.markRootTransitionActivated",
                        "directory.commitRootTransition",
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
                // The candidate remains uncommitted; recovery reopens the previous authority.
                directorySettingsRepository.appliedUpdates shouldBe emptyList()
                workspaceStateResolver.rebuildCallCount shouldBe 0
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

        test("updateRootLocation reactivates the same location when the engine is not Ready") {
            runTest {
                val location = StorageLocation("/tmp/lomo")
                directorySettingsRepository.setLocation(StorageArea.ROOT, location)
                engineReadinessRepository.activateWorkspace(location)
                engineReadinessRepository.publish(
                    EngineReadiness.ReadOnlyRecovery(
                        category = EngineFailureCategory.STORAGE,
                        code = "projection_refresh_failed",
                        retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                        diagnostic = "Workspace projection build failed",
                    ),
                )
                eventLog.clear()
                engineReadinessRepository.activateCount = 0

                useCase.updateRootLocation(location)

                engineReadinessRepository.activateCount shouldBe 1
                engineReadinessRepository.lastActivated shouldBe location
                engineReadinessRepository.readiness.value shouldBe EngineReadiness.Ready
                workspaceMutationLease.transitionCount shouldBe 1
                eventLog shouldBe listOf("workspace.validateCandidate")
            }
        }

        test("updateRootLocation is a no-op when the same Ready location is requested") {
            runTest {
                val location = StorageLocation("/tmp/lomo")
                directorySettingsRepository.setLocation(StorageArea.ROOT, location)
                engineReadinessRepository.activateWorkspace(location)
                eventLog.clear()
                engineReadinessRepository.activateCount = 0

                useCase.updateRootLocation(location)

                engineReadinessRepository.activateCount shouldBe 0
                workspaceMutationLease.transitionCount shouldBe 0
                eventLog shouldBe listOf("workspace.validateCandidate")
            }
        }

        test("rebuildCurrentWorkspace delegates to local workspace resolver") {
            runTest {
                useCase.rebuildCurrentWorkspace()

                workspaceStateResolver.rebuildCallCount shouldBe 1
                eventLog shouldBe listOf("workspace.rebuildFromCurrentWorkspace")
            }
        }

        test("updateRootLocation resumes a pending transition aimed at the same candidate") {
            runTest {
                // An interrupted earlier attempt left a PREPARED transition for the same target;
                // retrying is the same operation and must not mint a second transition journal.
                val candidate = StorageLocation("/tmp/candidate")
                val interrupted = directorySettingsRepository.prepareRootTransition(candidate)
                eventLog.clear()

                useCase.updateRootLocation(candidate)

                engineReadinessRepository.activateCount shouldBe 1
                engineReadinessRepository.lastActivated shouldBe candidate
                directorySettingsRepository.pendingRootTransition() shouldBe null
                directorySettingsRepository.currentRootLocation() shouldBe candidate
                eventLog shouldBe
                    listOf(
                        "workspace.validateCandidate",
                        "directory.markRootTransitionActivated",
                        "directory.commitRootTransition",
                    )
            }
        }

        test("updateRootLocation commits an already-activated transition aimed at the same candidate") {
            runTest {
                // A crash after activation left an ACTIVATED transition: resume re-asserts engine
                // activation and commits under the original operation id.
                val candidate = StorageLocation("/tmp/candidate")
                val interrupted = directorySettingsRepository.prepareRootTransition(candidate)
                directorySettingsRepository.markRootTransitionActivated(interrupted.id)
                eventLog.clear()

                useCase.updateRootLocation(candidate)

                engineReadinessRepository.activateCount shouldBe 1
                directorySettingsRepository.pendingRootTransition() shouldBe null
                directorySettingsRepository.currentRootLocation() shouldBe candidate
                eventLog shouldBe
                    listOf(
                        "workspace.validateCandidate",
                        "directory.commitRootTransition",
                    )
            }
        }

        test("updateRootLocation clears stale pending transition before starting a new transition") {
            runTest {
                // Simulate a leftover transition in datastore from a prior crash
                directorySettingsRepository.prepareRootTransition(StorageLocation("/tmp/stale"))
                eventLog.clear()

                val location = StorageLocation("/tmp/fresh")
                useCase.updateRootLocation(location)

                engineReadinessRepository.activateCount shouldBe 1
                engineReadinessRepository.lastActivated shouldBe location
                directorySettingsRepository.pendingRootTransition() shouldBe null
                eventLog shouldBe
                    listOf(
                        "workspace.validateCandidate",
                        "directory.rollbackRootTransition",
                        "directory.prepareRootTransition",
                        "directory.markRootTransitionActivated",
                        "directory.commitRootTransition",
                    )
            }
        }
    }
}
