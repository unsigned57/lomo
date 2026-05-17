/*
 * Test Contract:
 * - Unit under test: ToggleMemoCheckboxUseCaseTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for ToggleMemoCheckboxUseCaseTest.
 * - Boundary: boundary and edge cases for ToggleMemoCheckboxUseCaseTest.
 * - Failure: failure and error scenarios for ToggleMemoCheckboxUseCaseTest.
 * - Must-not-happen: invariants are never violated for ToggleMemoCheckboxUseCaseTest.
 *
 * - Behavior focus: test behavioral outcomes of ToggleMemoCheckboxUseCaseTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.usecase.ValidateMemoContentUseCase
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

class ToggleMemoCheckboxUseCaseTest : DomainFunSpec() {
    private val repository: MemoRepository = mockk(relaxed = true)
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
                        coVerify(exactly = 1) { repository.updateMemo(memo, "- [x] task\ntext") }
                    }
        }
    }
    init {
        test("supports uppercase X when unchecking") {
            runTest {
                        val memo = memo(content = "- [X] done")

                        val changed = useCase(memo = memo, lineIndex = 0, checked = false)

                        (changed) shouldBe true
                        coVerify(exactly = 1) { repository.updateMemo(memo, "- [ ] done") }
                    }
        }
    }
    init {
        test("does not toggle when checkbox marker only appears in body text") {
            runTest {
                        val memo = memo(content = "prefix - [ ] not a list item")

                        val changed = useCase(memo = memo, lineIndex = 0, checked = true)

                        (changed) shouldBe false
                        coVerify(exactly = 0) { repository.updateMemo(any(), any()) }
                    }
        }
    }
    init {
        test("supports ordered list checkbox markers") {
            runTest {
                        val memo = memo(content = "1. [ ] numbered task")

                        val changed = useCase(memo = memo, lineIndex = 0, checked = true)

                        (changed) shouldBe true
                        coVerify(exactly = 1) { repository.updateMemo(memo, "1. [x] numbered task") }
                    }
        }
    }
    init {
        test("preserves trailing newline when toggling checkbox") {
            runTest {
                        val memo = memo(content = "- [ ] task\n")

                        val changed = useCase(memo = memo, lineIndex = 0, checked = true)

                        (changed) shouldBe true
                        coVerify(exactly = 1) { repository.updateMemo(memo, "- [x] task\n") }
                    }
        }
    }
    init {
        test("preserves multiple trailing blank lines when toggling checkbox") {
            runTest {
                        val memo = memo(content = "- [ ] task\n\n")

                        val changed = useCase(memo = memo, lineIndex = 0, checked = true)

                        (changed) shouldBe true
                        coVerify(exactly = 1) { repository.updateMemo(memo, "- [x] task\n\n") }
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
