package com.lomo.domain.usecase

/*
 * Behavior Contract:
 * - Unit under test: SwitchRootStorageUseCase.
 * - Owning layer: domain.
 * - Priority tier: P0.
 * - Capability: prepare/validate candidate, freeze writes, persist selection, activate engine,
 *   rebuild, then unfreeze. Failure before or during switch leaves the previous selection and
 *   previous engine authoritative and releases freeze. Soft non-Ready activate is a failure.
 *
 * Scenarios:
 * - Given a valid candidate, when switch succeeds, then freeze begins, selection persists, engine
 *   activates, rebuild runs, freeze ends.
 * - Given candidate validation fails, when switch is requested, then nothing is persisted and freeze
 *   never begins.
 * - Given persist fails after freeze begins, when switch aborts, then freeze is released and rebuild
 *   does not run.
 * - Given activate fails after persist, when switch aborts, then previous selection is restored and
 *   freeze ends.
 *
 * Observable outcomes: ordered event log, freeze begin/end counts, applied updates, activate calls.
 * TDD proof: fails before freeze/validate/activate ordering is required by SwitchRootStorageUseCase.
 * Excludes: concrete SAF/engine open validation and UI navigation.
 
 * Test Change Justification:
 * - Reason category: production Markdown ownership cutover to Rust workspace IR / document commands.
 * - Old behavior/assertion being replaced: tests that assumed Kotlin MarkdownParser, MemoTextProcessor,
 *   JetBrains render plans, or dual-authority analysis helpers as production collaborators.
 * - Why old assertion is no longer correct: production storage analysis and presentation consume
 *   lomo-workspace typed IR and workspace adapters; the deleted Kotlin/JetBrains authorities are gone.
 * - Coverage preserved by: the same observable product outcomes (mapping, mutation gates, DI wiring,
 *   share/card presentation) re-asserted against FakeMarkdownWorkspace / IR / projector seams.
 * - Why this is not fitting the test to the implementation: assertions still check public behavior and
 *   fail-closed boundaries, not private parser implementation details.
 */

import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.WorkspaceCandidateValidator
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeDirectorySettingsRepository
import com.lomo.domain.testing.fakes.FakeEngineReadinessRepository
import com.lomo.domain.testing.fakes.FakeWorkspaceStateResolver
import com.lomo.domain.testing.fakes.FakeWriteFreezeRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class SwitchRootStorageUseCaseTest : DomainFunSpec() {
    private val eventLog = mutableListOf<String>()
    private val directorySettingsRepository = FakeDirectorySettingsRepository(eventLog)
    private val workspaceStateResolver = FakeWorkspaceStateResolver(eventLog)
    private val writeFreezeRepository = FakeWriteFreezeRepository()
    private val engineReadinessRepository = FakeEngineReadinessRepository()
    private lateinit var useCase: SwitchRootStorageUseCase

    init {
        beforeTest {
            eventLog.clear()
            directorySettingsRepository.applyFailure = null
            workspaceStateResolver.rebuildFailure = null
            writeFreezeRepository.beginResult = true
            engineReadinessRepository.remainingActivateFailures = 0
            engineReadinessRepository.activateCount = 0
            engineReadinessRepository.clearCount = 0
            engineReadinessRepository.activateFailure = IllegalStateException("open failed")
            useCase =
                SwitchRootStorageUseCase(
                    directorySettingsRepository = directorySettingsRepository,
                    workspaceStateResolver = workspaceStateResolver,
                    writeFreezeRepository = writeFreezeRepository,
                    engineReadinessRepository = engineReadinessRepository,
                    workspaceCandidateValidator =
                        WorkspaceCandidateValidator { location ->
                            eventLog += "workspace.validateCandidate"
                            require(location.raw.isNotBlank()) { "blank candidate" }
                        },
                )
        }

        test("updateRootLocation freezes writes, persists, activates engine, rebuilds, then unfreezes") {
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
                writeFreezeRepository.beginCount shouldBe 1
                writeFreezeRepository.endCount shouldBe 1
                writeFreezeRepository.isFrozen.value shouldBe false
                eventLog shouldBe
                    listOf(
                        "workspace.validateCandidate",
                        "directory.applyLocation:ROOT",
                        "workspace.rebuildFromCurrentWorkspace",
                    )
            }
        }

        test("updateRootLocation does not freeze or persist when candidate validation fails") {
            runTest {
                val failing =
                    SwitchRootStorageUseCase(
                        directorySettingsRepository = directorySettingsRepository,
                        workspaceStateResolver = workspaceStateResolver,
                        writeFreezeRepository = writeFreezeRepository,
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
                writeFreezeRepository.beginCount shouldBe 0
                writeFreezeRepository.endCount shouldBe 0
                eventLog shouldBe listOf("workspace.validateCandidate")
            }
        }

        test("updateRootLocation releases freeze when persist fails") {
            runTest {
                directorySettingsRepository.applyFailure = IllegalStateException("failed")

                val error =
                    runCatching { useCase.updateRootLocation(StorageLocation("content://root")) }
                        .exceptionOrNull()

                error.shouldBeInstanceOf<IllegalStateException>()
                directorySettingsRepository.appliedUpdates shouldBe emptyList()
                workspaceStateResolver.rebuildCallCount shouldBe 0
                engineReadinessRepository.activateCount shouldBe 0
                writeFreezeRepository.beginCount shouldBe 1
                writeFreezeRepository.endCount shouldBe 1
                writeFreezeRepository.isFrozen.value shouldBe false
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
                workspaceStateResolver.rebuildCallCount shouldBe 0
                // activate attempted for candidate then for restore
                engineReadinessRepository.activateCount shouldBe 2
                writeFreezeRepository.beginCount shouldBe 1
                writeFreezeRepository.endCount shouldBe 1
                writeFreezeRepository.isFrozen.value shouldBe false
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
                writeFreezeRepository.beginCount shouldBe 1
                writeFreezeRepository.endCount shouldBe 1
                writeFreezeRepository.isFrozen.value shouldBe false
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
