/*
 * Behavior Contract:
 * - Unit under test: ToggleMemoCheckboxUseCaseTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenarios:
 * - Happy: standard happy path for ToggleMemoCheckboxUseCaseTest.
 * - Boundary: boundary and edge cases for ToggleMemoCheckboxUseCaseTest.
 * - Failure: failure and error scenarios for ToggleMemoCheckboxUseCaseTest.
 * - Must-not-happen: invariants are never violated for ToggleMemoCheckboxUseCaseTest.
 *
 * - Behavior focus: test behavioral outcomes of ToggleMemoCheckboxUseCaseTest.
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


import com.lomo.domain.model.Memo
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeMemoRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class ToggleMemoCheckboxUseCaseTest : DomainFunSpec() {
    private val repository = FakeMemoRepository()
    private val useCase = ToggleMemoCheckboxUseCase(repository, ValidateMemoContentUseCase())
    init {
        test("updates markdown checkbox line when checking") {
            runTest {
                        val memo =
                            memo(
                                content = "- [ ] task\ntext",
                            )

                        val changed = useCase(memo = memo, lineIndex = 0, checked = true)

                        (changed) shouldBe true
                        repository.updatedMemos shouldBe
                            listOf(FakeMemoRepository.UpdatedMemo(memo, "- [x] task\ntext"))
                    }
        }

        test("supports uppercase X when unchecking") {
            runTest {
                        val memo = memo(content = "- [X] done")

                        val changed = useCase(memo = memo, lineIndex = 0, checked = false)

                        (changed) shouldBe true
                        repository.updatedMemos shouldBe
                            listOf(FakeMemoRepository.UpdatedMemo(memo, "- [ ] done"))
                    }
        }

        test("does not toggle when checkbox marker only appears in body text") {
            runTest {
                        val memo = memo(content = "prefix - [ ] not a list item")

                        val changed = useCase(memo = memo, lineIndex = 0, checked = true)

                        (changed) shouldBe false
                        repository.updatedMemos shouldBe emptyList()
                    }
        }

        test("supports ordered list checkbox markers") {
            runTest {
                        val memo = memo(content = "1. [ ] numbered task")

                        val changed = useCase(memo = memo, lineIndex = 0, checked = true)

                        (changed) shouldBe true
                        repository.updatedMemos shouldBe
                            listOf(FakeMemoRepository.UpdatedMemo(memo, "1. [x] numbered task"))
                    }
        }

        test("preserves trailing newline when toggling checkbox") {
            runTest {
                        val memo = memo(content = "- [ ] task\n")

                        val changed = useCase(memo = memo, lineIndex = 0, checked = true)

                        (changed) shouldBe true
                        repository.updatedMemos shouldBe
                            listOf(FakeMemoRepository.UpdatedMemo(memo, "- [x] task\n"))
                    }
        }

        test("preserves multiple trailing blank lines when toggling checkbox") {
            runTest {
                        val memo = memo(content = "- [ ] task\n\n")

                        val changed = useCase(memo = memo, lineIndex = 0, checked = true)

                        (changed) shouldBe true
                        repository.updatedMemos shouldBe
                            listOf(FakeMemoRepository.UpdatedMemo(memo, "- [x] task\n\n"))
                    }
        }
    }

    private fun memo(content: String): Memo =
        Memo(
            id = "memo",
            timestamp = 1L,
            content = content,
            rawContent = content,
            dateKey = "2026_02_24",
        )
}
