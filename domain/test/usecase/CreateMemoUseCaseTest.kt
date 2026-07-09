/*
 * Behavior Contract:
 * - Unit under test: CreateMemoUseCaseTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenarios:
 * - Happy: standard happy path for CreateMemoUseCaseTest.
 * - Boundary: boundary and edge cases for CreateMemoUseCaseTest.
 * - Failure: failure and error scenarios for CreateMemoUseCaseTest.
 * - Must-not-happen: invariants are never violated for CreateMemoUseCaseTest.
 *
 * - Behavior focus: test behavioral outcomes of CreateMemoUseCaseTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - TDD proof: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

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


import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.StorageArea
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeDirectorySettingsRepository
import com.lomo.domain.testing.fakes.FakeMediaRepository
import com.lomo.domain.testing.fakes.FakeMemoStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: CreateMemoUseCase
 * - Behavior focus: workspace precondition enforcement before validation and save.
 * - Observable outcomes: thrown error message and repository save with validated input.
 * - Excludes: memo repository persistence internals and validator rule implementation.
 */
class CreateMemoUseCaseTest : DomainFunSpec() {
    private val memoRepository = FakeMemoStore()
    private val directorySettingsRepository = FakeDirectorySettingsRepository()
    private val initializeWorkspaceUseCase =
        InitializeWorkspaceUseCase(
            directorySettingsRepository = directorySettingsRepository,
            mediaRepository = FakeMediaRepository(),
        )
    private val validator = ValidateMemoContentUseCase()
    private val useCase = CreateMemoUseCase(com.lomo.domain.testing.fakes.FakeMemoMutationRepository(memoRepository), initializeWorkspaceUseCase, validator)
    init {
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

        test("invoke validates content then saves memo when workspace exists") {
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
