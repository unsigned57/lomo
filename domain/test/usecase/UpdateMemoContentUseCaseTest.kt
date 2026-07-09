/*
 * Behavior Contract:
 * - Unit under test: UpdateMemoContentUseCaseTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenarios:
 * - Happy: standard happy path for UpdateMemoContentUseCaseTest.
 * - Boundary: boundary and edge cases for UpdateMemoContentUseCaseTest.
 * - Failure: failure and error scenarios for UpdateMemoContentUseCaseTest.
 * - Must-not-happen: invariants are never violated for UpdateMemoContentUseCaseTest.
 *
 * - Behavior focus: test behavioral outcomes of UpdateMemoContentUseCaseTest.
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


import com.lomo.domain.model.MemoConstraints
import com.lomo.domain.model.Memo
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeMemoStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: UpdateMemoContentUseCase
 * - Behavior focus: update-vs-trash branching and validation gate before mutation.
 * - Observable outcomes: invoked collaborator path, call ordering, and exception propagation.
 * - Excludes: repository implementation internals, parser behavior, and UI rendering.
 */
class UpdateMemoContentUseCaseTest : DomainFunSpec() {
    private val memo =
        Memo(
            id = "memo-1",
            timestamp = 1L,
            content = "old-content",
            rawContent = "- 10:00 old-content",
            dateKey = "2026_03_24",
        )

    private lateinit var repository: FakeMemoStore
    private lateinit var useCase: UpdateMemoContentUseCase

    init {
        beforeTest {
            repository = FakeMemoStore(initialMemos = listOf(memo))
            useCase =
                UpdateMemoContentUseCase(
                    repository = com.lomo.domain.testing.fakes.FakeMemoMutationRepository(repository),
                    validator = ValidateMemoContentUseCase(),
                    resolveMemoUpdateActionUseCase = ResolveMemoUpdateActionUseCase(),
                    deleteMemoUseCase = DeleteMemoUseCase(com.lomo.domain.testing.fakes.FakeMemoMutationRepository(repository)),
                )
        }

        test("blank content delegates to trash flow without validation or update") {
            runTest {
                useCase(memo, "   ")

                repository.deletedMemoRequests shouldBe listOf(memo)
                repository.updatedMemos shouldBe emptyList()
                repository.currentMemos() shouldBe emptyList()
            }
        }

        test("update flow validates first then persists updated content") {
            runTest {
                useCase(memo, "new-content")

                repository.updatedMemos shouldBe
                    listOf(FakeMemoStore.UpdatedMemo(memo, "new-content"))
                repository.deletedMemoRequests shouldBe emptyList()
                repository.currentMemos().single().content shouldBe "new-content"
            }
        }

        test("validation failure is propagated and update is skipped") {
            runTest {
                val invalidContent = "x".repeat(MemoConstraints.MAX_MEMO_LENGTH + 1)

                val thrown =
                    runCatching {
                        useCase(memo, invalidContent)
                    }.exceptionOrNull()

                thrown.shouldBeInstanceOf<MemoValidationException>()
                repository.updatedMemos shouldBe emptyList()
                repository.deletedMemoRequests shouldBe emptyList()
                repository.currentMemos() shouldBe listOf(memo)
            }
        }
    }
}
