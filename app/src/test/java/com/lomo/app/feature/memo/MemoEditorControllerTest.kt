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
 * - Behavior focus: create/edit opening state, markdown append behavior, visibility management, and close reset semantics.
 * - Observable outcomes: visible flag, editingMemo selection, input text/value selection, and appended markdown content.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
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
}
