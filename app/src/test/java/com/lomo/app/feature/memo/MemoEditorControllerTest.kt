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
    }

    init {
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
    }

    init {
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
    }

    init {
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
    }

    init {
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
