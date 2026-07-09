package com.lomo.app.feature.memo

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.Memo
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: MemoEditorController
 * - Behavior focus: create/edit opening state, markdown append behavior, compact/expanded mode transitions, visibility management, backfill timestamp selection, and close reset semantics.
 * - Observable outcomes: visible flag, editingMemo selection, editor mode, input text/value selection, back-press consumption, backfill timestamp state, and appended markdown content.
 * - Red phase: Fails before the fix because MemoEditorController has no long-form mode state, cannot expand/collapse the active session, and cannot consume back to collapse before dismiss.
 * - Excludes: Compose sheet rendering, activity-result launchers, and media save wiring.
 */
class MemoEditorControllerTest : AppFunSpec() {
    init {
        /*
         * Behavior Contract:
         * - Capability: Strict cursor-position attachment insertion.
         * - Given: A text editor with an active cursor position or range selection.
         * - When: `appendMarkdownBlock` or `appendImageMarkdown` is invoked.
         * - Then: The markdown content is inserted/replaced strictly at the cursor position, formatted as a proper block with clean newlines as necessary, and selection is placed at the end of insertion.
         */
        test("appendMarkdownBlock inserts strictly at active selection cursor and range") {
            val controller = MemoEditorController()

            // Scenario 1: Cursor is in the middle of text
            controller.openForCreate("Hello World")
            controller.updateInputValue(TextFieldValue("Hello World", TextRange(5)))

            controller.appendMarkdownBlock("![image](path)")
            controller.inputValue.text shouldBe "Hello\n![image](path)\n World"

            // Scenario 2: Selection range (replaces selected range)
            controller.openForCreate("Hello Beautiful World")
            controller.updateInputValue(TextFieldValue("Hello Beautiful World", TextRange(6, 15)))

            controller.appendMarkdownBlock("![image](path)")
            controller.inputValue.text shouldBe "Hello \n![image](path)\n World"
        }
        /*
         * Behavior Contract:
         * - Capability: List auto-continuation on Enter.
         * - Given: A text editor containing a list item (ordered, unordered, checkbox/task list).
         * - When: The user types a newline character (\n) at the end of a list item.
         * - Then:
         *   1. If the list item has non-blank content, the list format (bullet/number/checkbox/indent) is continued on the new line, with ordered list numbers incremented by 1.
         *   2. If the list item has blank content (meaning the user pressed Enter on an empty list item), the list marker is deleted to cleanly end the list.
         */
        test("updateInputValue auto continues list marker on newline and clears empty markers") {
            val controller = MemoEditorController()

            // Scenario 1: Unordered list item - non-empty -> auto continue
            controller.openForCreate("- Item A")
            controller.updateInputValue(TextFieldValue("- Item A\n", TextRange(9)))
            controller.inputValue.text shouldBe "- Item A\n- "
            controller.inputValue.selection shouldBe TextRange(11)

            // Scenario 2: Unordered list item - empty -> clear list marker
            controller.openForCreate("- ")
            controller.updateInputValue(TextFieldValue("- \n", TextRange(3)))
            controller.inputValue.text shouldBe "\n"
            controller.inputValue.selection shouldBe TextRange(1)

            // Scenario 3: Task list item - non-empty -> auto continue
            controller.openForCreate("  * [x] Task 1")
            controller.updateInputValue(TextFieldValue("  * [x] Task 1\n", TextRange(15)))
            controller.inputValue.text shouldBe "  * [x] Task 1\n  * [ ] "
            controller.inputValue.selection shouldBe TextRange(23)

            // Scenario 4: Ordered list item - non-empty -> auto continue and increment
            controller.openForCreate("9. Item 9")
            controller.updateInputValue(TextFieldValue("9. Item 9\n", TextRange(10)))
            controller.inputValue.text shouldBe "9. Item 9\n10. "
            controller.inputValue.selection shouldBe TextRange(14)

            // Scenario 5: Ordered list item - empty -> clear list marker
            controller.openForCreate("9. ")
            controller.updateInputValue(TextFieldValue("9. \n", TextRange(4)))
            controller.inputValue.text shouldBe "\n"
            controller.inputValue.selection shouldBe TextRange(1)
        }

        test("open for create edit append ensure visible and close update controller state") {
            val controller = MemoEditorController()
            val memo =
                Memo(
                    id = "memo-1",
                    timestamp = 10L,
                    content = "existing body",
                    rawContent = "- 10:00 existing body",
                    dateKey = "2026_03_26",
                )

            controller.openForCreate("draft")
            ((controller.isVisible)) shouldBe true
            (controller.editingMemo) shouldBe null
            (controller.inputValue.selection) shouldBe (TextRange(5))
            (controller.inputValue.text) shouldBe ("draft")

            controller.appendMarkdownBlock("- [ ] todo")
            controller.appendImageMarkdown("images/cover.jpg")
            (controller.inputValue.text) shouldBe ("draft\n- [ ] todo\n![image](images/cover.jpg)")

            controller.updateInputValue(TextFieldValue("manual", TextRange(2)))
            (controller.inputValue.text) shouldBe ("manual")
            (controller.inputValue.selection) shouldBe (TextRange(2))

            controller.openForEdit(memo)
            (controller.editingMemo) shouldBe (memo)
            (controller.inputValue.text) shouldBe ("existing body")
            (controller.inputValue.selection) shouldBe (TextRange(memo.content.length))

            controller.close()
            ((controller.isVisible)) shouldBe false
            (controller.editingMemo) shouldBe null
            (controller.inputValue.text) shouldBe ("")
        }

        test("append markdown uses single line when input is initially empty and ensureVisible reopens") {
            val controller = MemoEditorController()

            controller.openForCreate()
            controller.appendMarkdownBlock("```kotlin")
            controller.close()
            controller.ensureVisible()

            ((controller.isVisible)) shouldBe true
            (controller.inputValue.text) shouldBe ("")

            controller.openForCreate()
            controller.appendMarkdownBlock("```kotlin")
            (controller.inputValue.text) shouldBe ("```kotlin")
        }

        test("create session defaults to compact and expanded mode can collapse without losing draft") {
            val controller = MemoEditorController()

            controller.openForCreate("draft")

            (controller.mode) shouldBe (MemoEditorMode.Compact)
            (controller.inputValue.text) shouldBe ("draft")
            (controller.inputValue.selection) shouldBe (TextRange(5))

            controller.setExpanded(true)

            (controller.mode) shouldBe (MemoEditorMode.Expanded)
            (controller.inputValue.text) shouldBe ("draft")
            (controller.inputValue.selection) shouldBe (TextRange(5))

            ((controller.consumeBackPress())) shouldBe true
            (controller.mode) shouldBe (MemoEditorMode.Compact)
            (controller.inputValue.text) shouldBe ("draft")
            (controller.inputValue.selection) shouldBe (TextRange(5))

            ((controller.consumeBackPress())) shouldBe false

            controller.close()
            controller.openForCreate("fresh")

            (controller.mode) shouldBe (MemoEditorMode.Compact)
            (controller.inputValue.text) shouldBe ("fresh")
        }

        test("backfill timestamp is stored only for create sessions and resets with session boundaries") {
            val controller = MemoEditorController()
            val timestampMillis = 1_777_777_777_123L
            val memo =
                Memo(
                    id = "memo-edit",
                    timestamp = 10L,
                    content = "existing body",
                    rawContent = "- 10:00 existing body",
                    dateKey = "2026_03_26",
                )

            controller.openForCreate("draft")
            controller.backfillSelection.setTimestampForCreate(timestampMillis, isEditingExistingMemo = false)

            (controller.backfillSelection.timestampMillis) shouldBe (timestampMillis)
            (controller.backfillSelection.timestampMillisForCreateSubmit(false)) shouldBe (timestampMillis)

            controller.openForEdit(memo)

            (controller.backfillSelection.timestampMillis) shouldBe null
            (controller.backfillSelection.timestampMillisForCreateSubmit(true)) shouldBe null

            controller.openForCreate("fresh")
            (controller.backfillSelection.timestampMillis) shouldBe null

            controller.backfillSelection.setTimestampForCreate(timestampMillis, isEditingExistingMemo = false)
            controller.close()

            (controller.backfillSelection.timestampMillis) shouldBe null
        }

        test("backfill selection can be cancelled from badge without losing the create draft") {
            val controller = MemoEditorController()
            val timestampMillis = 1_777_777_777_123L

            controller.openForCreate("draft")
            controller.backfillSelection.setTimestampForCreate(timestampMillis, isEditingExistingMemo = false)

            controller.cancelBackfillSelection()

            ((controller.isVisible)) shouldBe true
            (controller.editingMemo) shouldBe null
            (controller.inputValue.text) shouldBe ("draft")
            (controller.backfillSelection.timestampMillis) shouldBe null
            (controller.backfillSelection.timestampMillisForCreateSubmit(false)) shouldBe null
        }
    }
}
