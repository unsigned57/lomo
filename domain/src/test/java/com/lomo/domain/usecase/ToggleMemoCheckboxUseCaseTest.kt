/*
 * Behavior Contract:
 * - Unit under test: ToggleMemoCheckboxUseCase
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: toggle a markdown task-list checkbox on a given line and persist the change.
 *
 * Scenarios:
 * - Given an unchecked task line, when toggled to checked, then the checked content is returned and persisted.
 * - Given a checked uppercase-X task line, when toggled to unchecked, then the unchecked content is returned and persisted.
 * - Given a checkbox marker that only appears in body text, when toggled, then null is returned and nothing persists.
 * - Given an ordered-list task line, when toggled, then the ordered marker is preserved in returned and persisted content.
 * - Given one or more trailing newlines, when toggled, then they are preserved exactly in returned and persisted content.
 *
 * Observable outcomes:
 * - The returned new content (or null when unchanged) and the persisted MemoMutationRepository update.
 *
 * TDD proof:
 * - RED: against the prior Boolean-returning contract, asserting the returned value equals the toggled markdown
 *   string fails to compile (Boolean vs String), proving the content-returning contract is required.
 * - RED command: `./gradlew :domain:test --tests 'com.lomo.domain.usecase.ToggleMemoCheckboxUseCaseTest'`.
 * - GREEN: invoke() returns the persisted new content, or null when the line is not a togglable checkbox.
 *
 * Excludes:
 * - Repository persistence internals, markdown rendering, and UI state.
 *
 * Test Change Justification:
 * - Reason category: production contract change.
 * - Old behavior/assertion being replaced: invoke() returned Boolean and tests asserted true/false.
 * - Why old assertion is no longer correct: invoke() now returns the new content (or null) so snapshot-backed
 *   collections (Daily Review) can apply an optimistic update without re-parsing the checkbox line.
 * - Coverage preserved by: asserting the exact toggled markdown string is returned and matches the persisted update.
 * - Why this is not fitting the test to the implementation: each assertion encodes the intended toggled markdown
 *   for that scenario, not whatever the implementation happens to emit.
 */

package com.lomo.domain.usecase


import com.lomo.domain.model.Memo
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeMemoStore
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class ToggleMemoCheckboxUseCaseTest : DomainFunSpec() {
    private val repository = FakeMemoStore()
    private val useCase = ToggleMemoCheckboxUseCase(com.lomo.domain.testing.fakes.FakeMemoMutationRepository(repository), ValidateMemoContentUseCase())
    init {
        test("updates markdown checkbox line when checking") {
            runTest {
                val memo = memo(content = "- [ ] task\ntext")

                val result = useCase(memo = memo, lineIndex = 0, checked = true)

                result shouldBe "- [x] task\ntext"
                repository.updatedMemos shouldBe
                    listOf(FakeMemoStore.UpdatedMemo(memo, "- [x] task\ntext"))
            }
        }

        test("supports uppercase X when unchecking") {
            runTest {
                val memo = memo(content = "- [X] done")

                val result = useCase(memo = memo, lineIndex = 0, checked = false)

                result shouldBe "- [ ] done"
                repository.updatedMemos shouldBe
                    listOf(FakeMemoStore.UpdatedMemo(memo, "- [ ] done"))
            }
        }

        test("does not toggle when checkbox marker only appears in body text") {
            runTest {
                val memo = memo(content = "prefix - [ ] not a list item")

                val result = useCase(memo = memo, lineIndex = 0, checked = true)

                result shouldBe null
                repository.updatedMemos shouldBe emptyList()
            }
        }

        test("supports ordered list checkbox markers") {
            runTest {
                val memo = memo(content = "1. [ ] numbered task")

                val result = useCase(memo = memo, lineIndex = 0, checked = true)

                result shouldBe "1. [x] numbered task"
                repository.updatedMemos shouldBe
                    listOf(FakeMemoStore.UpdatedMemo(memo, "1. [x] numbered task"))
            }
        }

        test("preserves trailing newline when toggling checkbox") {
            runTest {
                val memo = memo(content = "- [ ] task\n")

                val result = useCase(memo = memo, lineIndex = 0, checked = true)

                result shouldBe "- [x] task\n"
                repository.updatedMemos shouldBe
                    listOf(FakeMemoStore.UpdatedMemo(memo, "- [x] task\n"))
            }
        }

        test("preserves multiple trailing blank lines when toggling checkbox") {
            runTest {
                val memo = memo(content = "- [ ] task\n\n")

                val result = useCase(memo = memo, lineIndex = 0, checked = true)

                result shouldBe "- [x] task\n\n"
                repository.updatedMemos shouldBe
                    listOf(FakeMemoStore.UpdatedMemo(memo, "- [x] task\n\n"))
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
