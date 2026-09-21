/*
 * Behavior Contract:
 * Capability: admit a complete edit baseline and validate body before mutation; owner: domain; P0.
 * Scenarios: Given a complete snapshot, when valid text is submitted, then its body is updated;
 * blank/oversized bodies fail without deletion or modification. Preview and missing baselines
 * cannot construct the edit attempt consumed by the repository.
 * Observable outcomes: persisted fake memo, unchanged memo on rejection and validation failures.
 * TDD proof: operation-id input RED and command conversion evidence in audit09 command test logs.
 * Excludes: native persistence and Markdown parsing.
 * Test Change Justification:
 * Reason category: stronger editor input contract.
 * Old behavior/assertion being replaced: a generic Memo with no baseline could invoke update.
 * Why old assertion is no longer correct: update requires a complete snapshot and frozen operation.
 * Coverage preserved by: valid/blank/oversized body outcomes with explicit immutable baselines.
 * Why this is not fitting the test to the implementation: invalid requests are rejected before I/O.
 */
package com.lomo.domain.usecase

import com.lomo.domain.model.EditableMemoSnapshot
import com.lomo.domain.model.MemoUpdateAttempt
import com.lomo.domain.model.MemoOperationId
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
            contentRevision = 1L,
            fileFingerprint = "source-fingerprint",
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
                )
        }

        test("blank content is rejected without deleting or updating the memo") {
            runTest {
                val thrown =
                    runCatching {
                        useCase(MemoUpdateAttempt(MemoOperationId("update-test"), com.lomo.domain.model.DraftId("draft-test"), EditableMemoSnapshot.fromFullSnapshot(memo), "   "))
                    }.exceptionOrNull()

                thrown.shouldBeInstanceOf<MemoValidationException>()
                repository.deletedMemoRequests shouldBe emptyList()
                repository.updatedMemos shouldBe emptyList()
                repository.currentMemos() shouldBe listOf(memo)
            }
        }

        test("update flow validates first then persists updated content") {
            runTest {
                useCase(MemoUpdateAttempt(MemoOperationId("update-test"), com.lomo.domain.model.DraftId("draft-test"), EditableMemoSnapshot.fromFullSnapshot(memo), "new-content"))

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
                        useCase(MemoUpdateAttempt(MemoOperationId("update-test"), com.lomo.domain.model.DraftId("draft-test"), EditableMemoSnapshot.fromFullSnapshot(memo), invalidContent))
                    }.exceptionOrNull()

                thrown.shouldBeInstanceOf<MemoValidationException>()
                repository.updatedMemos shouldBe emptyList()
                repository.deletedMemoRequests shouldBe emptyList()
                repository.currentMemos() shouldBe listOf(memo)
            }
        }
    }
}
