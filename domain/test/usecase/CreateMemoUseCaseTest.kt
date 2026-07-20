/*
 * Behavior Contract:
 * - Unit under test: CreateMemoUseCase
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: enforce the Rust engine write hard gate and workspace selection before validation
 *   and save.
 *
 * Scenarios:
 * - Given engine is not Ready, when create is requested, then the call fails closed without save.
 * - Given engine Ready but workspace root is missing, when create is requested, then selection
 *   error is raised without save.
 * - Given engine Ready and workspace exists, when content is valid, then the memo is saved.
 *
 * Observable outcomes: thrown error message and repository save with validated input.
 * TDD proof: fails before engine readiness is required by CreateMemoUseCase.
 * Excludes: memo repository persistence internals and validator rule implementation.
 
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

package com.lomo.domain.usecase

import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeDirectorySettingsRepository
import com.lomo.domain.testing.fakes.FakeEngineReadinessRepository
import com.lomo.domain.testing.fakes.FakeMediaRepository
import com.lomo.domain.testing.fakes.FakeMemoMutationRepository
import com.lomo.domain.testing.fakes.FakeMemoStore
import com.lomo.domain.testing.fakes.FakeWriteFreezeRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class CreateMemoUseCaseTest : DomainFunSpec() {
    private val memoRepository = FakeMemoStore()
    private val directorySettingsRepository = FakeDirectorySettingsRepository()
    private val engineReadinessRepository = FakeEngineReadinessRepository()
    private val writeFreezeRepository = FakeWriteFreezeRepository()
    private val initializeWorkspaceUseCase =
        InitializeWorkspaceUseCase(
            directorySettingsRepository = directorySettingsRepository,
            mediaRepository = FakeMediaRepository(),
        )
    private val validator = ValidateMemoContentUseCase()
    private val useCase =
        CreateMemoUseCase(
            FakeMemoMutationRepository(memoRepository),
            initializeWorkspaceUseCase,
            validator,
            engineReadinessRepository,
            writeFreezeRepository,
        )

    init {
        test("invoke fails closed when engine is not Ready") {
            runTest {
                engineReadinessRepository.publish(
                    EngineReadiness.ReadOnlyRecovery(
                        category = EngineReadiness.FailureCategory.PERMISSION,
                        code = "saf_grant_revoked",
                        retryDisposition = EngineReadiness.RetryDisposition.AFTER_USER_ACTION,
                        diagnostic = "Workspace permission is no longer available",
                    ),
                )
                directorySettingsRepository.setLocation(StorageArea.ROOT, StorageLocation("/workspace"))

                val error =
                    runCatching {
                        useCase(content = "new memo", timestampMillis = 123L)
                    }.exceptionOrNull()

                val blocked = error.shouldBeInstanceOf<IllegalStateException>()
                blocked.message.shouldContain("read-only recovery")
                memoRepository.savedMemos shouldBe emptyList()
            }
        }

        test("invoke fails closed when write freeze is active") {
            runTest {
                directorySettingsRepository.setLocation(StorageArea.ROOT, StorageLocation("/workspace"))
                writeFreezeRepository.begin()

                val error =
                    runCatching {
                        useCase(content = "new memo", timestampMillis = 123L)
                    }.exceptionOrNull()

                val blocked = error.shouldBeInstanceOf<IllegalStateException>()
                blocked.message.shouldContain("switch is in progress")
                memoRepository.savedMemos shouldBe emptyList()
            }
        }

        test("invoke fails fast when workspace root is missing") {
            runTest {
                val error =
                    runCatching {
                        useCase(content = "new memo", timestampMillis = 123L)
                    }.exceptionOrNull()

                val missingWorkspace = error.shouldBeInstanceOf<IllegalStateException>()
                missingWorkspace.message shouldBe "Please select a folder first"
                memoRepository.savedMemos shouldBe emptyList()
            }
        }

        test("invoke validates content then saves memo when engine is Ready and workspace exists") {
            runTest {
                directorySettingsRepository.setLocation(StorageArea.ROOT, StorageLocation("/workspace"))

                useCase(content = "meaningful note", timestampMillis = 456L)

                memoRepository.savedMemos shouldBe
                    listOf(
                        FakeMemoStore.SavedMemo(
                            content = "meaningful note",
                            timestamp = 456L,
                            geoLocation = null,
                        ),
                    )
            }
        }

        test("invoke returns saved Memo so callers can use the new memo id for deep links") {
            runTest {
                directorySettingsRepository.setLocation(StorageArea.ROOT, StorageLocation("/workspace"))

                val saved = useCase(content = "voice memo", timestampMillis = 789L)

                saved.content shouldBe "voice memo"
                saved.timestamp shouldBe 789L
                saved.id shouldNotBe null
                saved.id shouldNotBe ""
            }
        }
    }
}
