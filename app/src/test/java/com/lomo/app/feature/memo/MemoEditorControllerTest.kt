package com.lomo.app.feature.memo

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.lomo.domain.model.Memo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: MemoEditorController
 * - Behavior focus: create/edit opening state, markdown append behavior, compact/expanded mode transitions, visibility management, backfill timestamp selection, and close reset semantics.
 * - Observable outcomes: visible flag, editingMemo selection, editor mode, input text/value selection, back-press consumption, backfill timestamp state, and appended markdown content.
 * - Red phase: Fails before the fix because MemoEditorController has no long-form mode state, cannot expand/collapse the active session, and cannot consume back to collapse before dismiss.
 * - Excludes: Compose sheet rendering, activity-result launchers, and media save wiring.
 */
class MemoEditorControllerTest {
    @Test
    fun `open for create edit append ensure visible and close update controller state`() {
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
        assertTrue(controller.isVisible)
        assertNull(controller.editingMemo)
        assertEquals(TextRange(5), controller.inputValue.selection)
        assertEquals("draft", controller.inputValue.text)

        controller.appendMarkdownBlock("- [ ] todo")
        controller.appendImageMarkdown("images/cover.jpg")
        assertEquals("draft\n- [ ] todo\n![image](images/cover.jpg)", controller.inputValue.text)

        controller.updateInputValue(TextFieldValue("manual", TextRange(2)))
        assertEquals("manual", controller.inputValue.text)
        assertEquals(TextRange(2), controller.inputValue.selection)

        controller.openForEdit(memo)
        assertEquals(memo, controller.editingMemo)
        assertEquals("existing body", controller.inputValue.text)
        assertEquals(TextRange(memo.content.length), controller.inputValue.selection)

        controller.close()
        assertFalse(controller.isVisible)
        assertNull(controller.editingMemo)
        assertEquals("", controller.inputValue.text)
    }

    @Test
    fun `append markdown uses single line when input is initially empty and ensureVisible reopens`() {
        val controller = MemoEditorController()

        controller.openForCreate()
        controller.appendMarkdownBlock("```kotlin")
        controller.close()
        controller.ensureVisible()

        assertTrue(controller.isVisible)
        assertEquals("", controller.inputValue.text)

        controller.openForCreate()
        controller.appendMarkdownBlock("```kotlin")
        assertEquals("```kotlin", controller.inputValue.text)
    }

    @Test
    fun `create session defaults to compact and expanded mode can collapse without losing draft`() {
        val controller = MemoEditorController()

        controller.openForCreate("draft")

        assertEquals(MemoEditorMode.Compact, controller.mode)
        assertEquals("draft", controller.inputValue.text)
        assertEquals(TextRange(5), controller.inputValue.selection)

        controller.setExpanded(true)

        assertEquals(MemoEditorMode.Expanded, controller.mode)
        assertEquals("draft", controller.inputValue.text)
        assertEquals(TextRange(5), controller.inputValue.selection)

        assertTrue(controller.consumeBackPress())
        assertEquals(MemoEditorMode.Compact, controller.mode)
        assertEquals("draft", controller.inputValue.text)
        assertEquals(TextRange(5), controller.inputValue.selection)

        assertFalse(controller.consumeBackPress())

        controller.close()
        controller.openForCreate("fresh")

        assertEquals(MemoEditorMode.Compact, controller.mode)
        assertEquals("fresh", controller.inputValue.text)
    }

    @Test
    fun `backfill timestamp is stored only for create sessions and resets with session boundaries`() {
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

        assertEquals(timestampMillis, controller.backfillSelection.timestampMillis)
        assertEquals(timestampMillis, controller.backfillSelection.timestampMillisForCreateSubmit(false))

        controller.openForEdit(memo)

        assertNull(controller.backfillSelection.timestampMillis)
        assertNull(controller.backfillSelection.timestampMillisForCreateSubmit(true))

        controller.openForCreate("fresh")
        assertNull(controller.backfillSelection.timestampMillis)

        controller.backfillSelection.setTimestampForCreate(timestampMillis, isEditingExistingMemo = false)
        controller.close()

        assertNull(controller.backfillSelection.timestampMillis)
    }

    @Test
    fun `backfill selection can be cancelled from badge without losing the create draft`() {
        val controller = MemoEditorController()
        val timestampMillis = 1_777_777_777_123L

        controller.openForCreate("draft")
        controller.backfillSelection.setTimestampForCreate(timestampMillis, isEditingExistingMemo = false)

        controller.cancelBackfillSelection()

        assertTrue(controller.isVisible)
        assertNull(controller.editingMemo)
        assertEquals("draft", controller.inputValue.text)
        assertNull(controller.backfillSelection.timestampMillis)
        assertNull(controller.backfillSelection.timestampMillisForCreateSubmit(false))
    }
}
